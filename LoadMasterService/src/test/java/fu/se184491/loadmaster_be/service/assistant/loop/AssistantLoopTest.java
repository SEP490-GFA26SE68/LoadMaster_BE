package fu.se184491.loadmaster_be.service.assistant.loop;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;
import fu.se184491.loadmaster_be.service.assistant.support.FakeChatModel;
import fu.se184491.loadmaster_be.service.assistant.support.FakeTool;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vòng gọi công cụ với mô hình và công cụ giả: không gọi mạng. */
class AssistantLoopTest {

    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final String TRIP = "{\"tripCode\":\"TRIP-2026-0914\",\"status\":\"PLANNED\"}";

    private final AssistantProperties properties = new AssistantProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final AssistantLoop loop = new AssistantLoop(properties, clock);
    private final FakeChatModel model = new FakeChatModel();
    private final TokenMeter meter = new TokenMeter();

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("Bạn là Lumo."), new UserMessage("Chuyến TRIP-2026-0914 thế nào?")));
    }

    private LoopResult run(List<ToolCallback> tools) {
        return loop.run(model, prompt(), tools, "vi", meter);
    }

    private static AnthropicChatOptions options(Prompt prompt) {
        return (AnthropicChatOptions) prompt.getOptions();
    }

    private static ToolResponseMessage.ToolResponse lastToolResponse(Prompt prompt) {
        List<Message> history = prompt.getInstructions();
        ToolResponseMessage message = (ToolResponseMessage) history.get(history.size() - 1);
        assertThat(message.getResponses()).hasSize(1);
        return message.getResponses().get(0);
    }

    @Test
    void toolRunsOnceAndTheSecondRoundAnswersWithBothRoundsCounted() {
        FakeTool getTrip = FakeTool.returning("get_trip", TRIP);
        model.toolCall("get_trip", "{\"tripCode\":\"TRIP-2026-0914\"}", 1000, 40)
                .reply("Chuyến TRIP-2026-0914 đã lập kế hoạch.", 1100, 20);

        LoopResult result = run(List.of(getTrip));

        assertThat(getTrip.calls()).containsExactly("{\"tripCode\":\"TRIP-2026-0914\"}");
        assertThat(result.text()).isEqualTo("Chuyến TRIP-2026-0914 đã lập kế hoạch.");
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.usage()).isEqualTo(new TokenUsage(2100, 60));
        assertThat(meter.total()).isEqualTo(new TokenUsage(2100, 60));
        assertThat(model.calls()).isEqualTo(2);

        // Lượt hai mang theo lời gọi công cụ và kết quả nguyên dạng
        Prompt second = model.prompts().get(1);
        List<Message> history = second.getInstructions();
        assertThat(history).hasSize(4);
        assertThat(((AssistantMessage) history.get(2)).getToolCalls()).singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("get_trip"));
        ToolResponseMessage.ToolResponse response = lastToolResponse(second);
        assertThat(response.id()).isEqualTo("toolu_0");
        assertThat(response.name()).isEqualTo("get_trip");
        assertThat(response.responseData()).isEqualTo(TRIP);
    }

    @Test
    void everyRoundKeepsTheModelDefaultsAndDisablesParallelToolUse() {
        model.toolCall("get_trip", "{}", 10, 1).reply("Xong.", 10, 1);

        run(List.of(FakeTool.returning("get_trip", TRIP)));

        for (Prompt prompt : model.prompts()) {
            AnthropicChatOptions options = options(prompt);
            assertThat(options.getModel()).isEqualTo(FakeChatModel.MODEL);
            assertThat(options.getMaxTokens()).isEqualTo(FakeChatModel.MAX_TOKENS);
            assertThat(options.getDisableParallelToolUse()).isTrue();
            assertThat(options.getToolCallbacks()).extracting(tool -> tool.getToolDefinition().name())
                    .containsExactly("get_trip");
            assertThat(options.getToolChoice()).isNull();
        }
    }

    @Test
    void modelAskingForToolsForeverStopsAfterFiveRoundsAndTheSixthCallCannotUseTools() {
        FakeTool getTrip = FakeTool.returning("get_trip", TRIP);
        model.toolCallAlways("get_trip", "{}", 100, 10);

        LoopResult result = run(List.of(getTrip));

        assertThat(getTrip.calls()).hasSize(5);
        assertThat(model.calls()).isEqualTo(6);
        for (int round = 0; round < 5; round++) {
            assertThat(options(model.prompts().get(round)).getToolChoice()).isNull();
        }
        // Lịch sử đã có tool_use nên vẫn khai công cụ, nhưng cấm gọi
        AnthropicChatOptions last = options(model.prompts().get(5));
        assertThat(last.getToolChoice().isNone()).isTrue();
        assertThat(last.getToolCallbacks()).hasSize(1);
        // Mô hình vẫn đòi công cụ ở lượt cuối: không chạy, không có chữ
        assertThat(result.text()).isEmpty();
        assertThat(result.usage()).isEqualTo(new TokenUsage(600, 60));
    }

    @Test
    void toolFailureBecomesAnErrorCodeAndTheLoopGoesOnWithoutLeakingTheMessage() {
        FakeTool broken = FakeTool.running("get_trip", arguments -> {
            throw new IllegalStateException("password=hunter2 at db.internal:5432");
        });
        model.toolCall("get_trip", "{}", 10, 1).reply("Mình chưa đọc được chuyến này.", 10, 1);

        LoopResult result = run(List.of(broken));

        String data = lastToolResponse(model.prompts().get(1)).responseData();
        assertThat(data).isEqualTo("{\"error\":\"TOOL_FAILED\"}");
        assertThat(result.text()).isEqualTo("Mình chưa đọc được chuyến này.");
    }

    @Test
    void appExceptionFromAToolIsReturnedAsItsCodeEvenWhenWrapped() {
        FakeTool direct = FakeTool.running("get_trip", arguments -> {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        });
        FakeTool wrapped = FakeTool.running("list_trips", arguments -> {
            throw new ToolExecutionException(ToolDefinition.builder().name("list_trips").description("d")
                    .inputSchema("{}").build(), new AppException(ErrorCode.INVALID_INPUT));
        });
        model.toolCall("get_trip", "{}", 10, 1).toolCall("list_trips", "{}", 10, 1).reply("Không thấy.", 10, 1);

        run(List.of(direct, wrapped));

        assertThat(lastToolResponse(model.prompts().get(1)).responseData()).isEqualTo("{\"error\":\"TRIP_NOT_FOUND\"}");
        assertThat(lastToolResponse(model.prompts().get(2)).responseData()).isEqualTo("{\"error\":\"INVALID_INPUT\"}");
    }

    @Test
    void toolOutsideTheScopeIsNotRun() {
        FakeTool getTrip = FakeTool.returning("get_trip", TRIP);
        model.toolCall("get_company_credits", "{}", 10, 1).reply("Mình không xem được mục đó.", 10, 1);

        LoopResult result = run(List.of(getTrip));

        assertThat(getTrip.calls()).isEmpty();
        assertThat(lastToolResponse(model.prompts().get(1)).responseData()).isEqualTo("{\"error\":\"TOOL_FAILED\"}");
        assertThat(result.text()).isEqualTo("Mình không xem được mục đó.");
    }

    @Test
    void toolCallCutOffByMaxTokensIsNotRun() {
        FakeTool getTrip = FakeTool.returning("get_trip", TRIP);
        model.toolCall("get_trip", "{\"tripCode\":\"TRIP-20", "max_tokens", 10, 800);

        LoopResult result = run(List.of(getTrip));

        assertThat(getTrip.calls()).isEmpty();
        assertThat(model.calls()).isEqualTo(1);
        assertThat(result.stopReason()).isEqualTo("max_tokens");
        assertThat(result.text()).isEmpty();
    }

    @Test
    void turnPastTheTimeoutStartsNoNewRoundButKeepsTheTokensSpent() {
        FakeTool slow = FakeTool.running("get_trip", arguments -> {
            clock.set(T0.plusSeconds(properties.getTurnTimeoutSeconds() + 1L));
            return TRIP;
        });
        model.toolCall("get_trip", "{}", 1000, 40).reply("Không bao giờ tới.", 10, 1);

        assertThatThrownBy(() -> run(List.of(slow)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(model.calls()).isEqualTo(1);
        assertThat(meter.total()).isEqualTo(new TokenUsage(1000, 40));
    }

    @Test
    void modelFailureInALaterRoundIsUnavailableAndEarlierTokensStayCounted() {
        model.toolCall("get_trip", "{}", 500, 20).fail(new RuntimeException("connect timed out"));

        assertThatThrownBy(() -> run(List.of(FakeTool.returning("get_trip", TRIP))))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(meter.total()).isEqualTo(new TokenUsage(500, 20));
    }

    @Test
    void refusalAfterAToolRoundReturnsTheFixedRefusal() {
        model.toolCall("get_trip", "{}", 10, 1).reply("", "refusal", 10, 1);

        LoopResult result = run(List.of(FakeTool.returning("get_trip", TRIP)));

        assertThat(result.stopReason()).isEqualTo("refusal");
        assertThat(result.text()).isEqualTo(AssistantLoop.REFUSAL_VI);
    }

    @Test
    void withoutToolsThePromptIsSentUnchanged() {
        model.reply("Xin chào", 1200, 30);
        Prompt prompt = prompt();

        LoopResult result = loop.run(model, prompt, List.of(), "vi", meter);

        assertThat(model.prompts()).containsExactly(prompt);
        assertThat(result.text()).isEqualTo("Xin chào");
        assertThat(meter.total()).isEqualTo(new TokenUsage(1200, 30));
    }
}
