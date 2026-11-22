package fu.se184491.loadmaster_be.service.assistant.loop;

import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceNone;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.assistant.tool.action.LinkTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vòng gọi mô hình của một lượt. Spring AI 2.0 không tự chạy công cụ trong {@code ChatModel.call}, nên vòng do code
 * này điều khiển: gọi mô hình, chạy công cụ mô hình đòi (lần lượt, trên luồng của request vì công cụ đọc
 * {@code SecurityContext}), gửi kết quả về, lặp tối đa {@code maxToolRounds} vòng. Hết vòng mà mô hình còn đòi công cụ
 * thì gọi thêm một lần với {@code tool_choice: none} — vẫn khai công cụ vì lịch sử đã có khối {@code tool_use}.
 * Câu trả lời là chữ của mọi vòng ghép theo thứ tự: mô hình hay viết câu trả lời rồi mới gắn liên kết
 * ({@code suggest_link}) trong cùng một tin. Chữ viết trước một công cụ khác (tra cứu, chuyển nhân viên) thường là câu
 * dẫn mà vòng sau viết lại đầy đủ, nên chỉ giữ khi không vòng nào sau đó có chữ.
 *
 * <p>Lỗi mạng, hết giờ, lỗi API đều thành {@code ASSISTANT_UNAVAILABLE}; cả lượt chạy quá {@code turnTimeoutSeconds}
 * thì không mở vòng mới. Công cụ lỗi không làm hỏng lượt: mô hình nhận {@code {"error":"<mã>"}} — mã của
 * {@link AppException}, còn lại là {@code TOOL_FAILED}, không bao giờ là thông điệp của ngoại lệ.
 */
@Slf4j
@Component
public class AssistantLoop {

    static final String REFUSAL = "refusal";
    static final String TOOL_USE = "tool_use";
    static final String TOOL_FAILED = ToolResults.TOOL_FAILED;
    static final String REFUSAL_VI = "Mình không thể giúp với yêu cầu này. Bạn có thể hỏi mình về cách dùng LoadMaster.";
    static final String REFUSAL_EN = "I can't help with that request. You can ask me about using LoadMaster.";
    private static final int MAX_CAUSE_DEPTH = 8;

    private final AssistantProperties properties;
    private final Clock clock;

    @Autowired
    public AssistantLoop(AssistantProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AssistantLoop(AssistantProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Chạy một lượt. {@code tools} rỗng thì gửi nguyên {@code prompt} một lần. Mỗi lần gọi mô hình cộng token vào
     * {@code meter} ngay khi có phản hồi, trước khi có thể ném lỗi ở vòng sau.
     */
    public LoopResult run(ChatModel model, Prompt prompt, List<ToolCallback> tools, String locale, TokenMeter meter) {
        Map<String, ToolCallback> byName = byName(tools);
        boolean withTools = !byName.isEmpty();
        int maxRounds = Math.max(0, properties.getMaxToolRounds());
        Instant deadline = clock.instant().plusSeconds(properties.getTurnTimeoutSeconds());
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        List<Said> said = new ArrayList<>();
        TokenUsage spent = TokenUsage.ZERO;

        for (int round = 0; ; round++) {
            if (round > 0 && clock.instant().isAfter(deadline)) {
                log.warn("Lượt của trợ lý quá {} giây sau {} vòng công cụ", properties.getTurnTimeoutSeconds(), round);
                throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
            }
            boolean lastRound = !withTools || round >= maxRounds;
            Prompt request = withTools ? new Prompt(List.copyOf(history), options(model, tools, lastRound)) : prompt;
            Step step = call(model, request);
            meter.add(step.usage());
            spent = spent.plus(step.usage());

            if (REFUSAL.equals(step.stopReason())) {
                return new LoopResult(refusal(locale), spent, REFUSAL, step.model());
            }
            AssistantMessage output = step.output();
            boolean done = lastRound || !TOOL_USE.equals(step.stopReason()) || !output.hasToolCalls();
            if (StringUtils.hasText(output.getText())) {
                said.add(new Said(output.getText().strip(), !done && !onlyLinks(output.getToolCalls())));
            }
            if (done) {
                return new LoopResult(answer(said), spent, step.stopReason(), step.model());
            }
            history.add(output);
            history.add(runTools(output.getToolCalls(), byName));
        }
    }

    private static boolean onlyLinks(List<AssistantMessage.ToolCall> calls) {
        return calls.stream().allMatch(call -> LinkTool.NAME.equals(call.name()));
    }

    /** Câu dẫn chỉ còn lại khi là chữ cuối cùng của lượt (vòng sau không viết gì). */
    private static String answer(List<Said> said) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < said.size(); i++) {
            if (!said.get(i).preamble() || i == said.size() - 1) {
                kept.add(said.get(i).text());
            }
        }
        return String.join("\n\n", kept);
    }

    static String refusal(String locale) {
        return "en".equalsIgnoreCase(locale) ? REFUSAL_EN : REFUSAL_VI;
    }

    private static Map<String, ToolCallback> byName(List<ToolCallback> tools) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback tool : tools == null ? List.<ToolCallback>of() : tools) {
            map.put(tool.getToolDefinition().name(), tool);
        }
        return map;
    }

    /**
     * Tuỳ chọn ở mức prompt thay hẳn tuỳ chọn mặc định của {@code AnthropicChatModel} (không gộp), nên dựng từ
     * tuỳ chọn của chính mô hình rồi thêm công cụ: giữ model, max_tokens, thinking, effort, bộ đệm.
     */
    private static AnthropicChatOptions options(ChatModel model, List<ToolCallback> tools, boolean lastRound) {
        AnthropicChatOptions.Builder builder = model.getOptions() instanceof AnthropicChatOptions defaults
                ? defaults.mutate()
                : AnthropicChatOptions.builder();
        builder.toolCallbacks(tools).disableParallelToolUse(true);
        if (lastRound) {
            builder.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
        }
        return builder.build();
    }

    private static Step call(ChatModel model, Prompt request) {
        ChatResponse response;
        try {
            response = model.call(request);
        } catch (RuntimeException e) {
            // Không ghi prompt hay chữ người dùng vào log
            log.warn("Gọi mô hình của trợ lý thất bại: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput() == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String stopReason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        String modelName = response.getMetadata() == null ? null : response.getMetadata().getModel();
        return new Step(result.getOutput(), usageOf(response),
                stopReason == null ? null : stopReason.toLowerCase(Locale.ROOT), modelName);
    }

    private static ToolResponseMessage runTools(List<AssistantMessage.ToolCall> calls, Map<String, ToolCallback> byName) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : calls) {
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    execute(call, byName.get(call.name()))));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private static String execute(AssistantMessage.ToolCall call, ToolCallback tool) {
        if (tool == null) {
            // Công cụ ngoài phạm vi của người hỏi: không ghi tên do mô hình viết vào log
            log.warn("Mô hình gọi một công cụ không có trong phạm vi của lượt");
            return error(TOOL_FAILED);
        }
        try {
            String result = tool.call(StringUtils.hasText(call.arguments()) ? call.arguments() : "{}");
            return result == null ? "{}" : result;
        } catch (RuntimeException e) {
            AppException app = appExceptionIn(e);
            if (app != null) {
                return error(app.getErrorCode().name());
            }
            log.warn("Công cụ {} của trợ lý lỗi: {}", call.name(), e.getClass().getSimpleName());
            return error(TOOL_FAILED);
        }
    }

    /** Công cụ dựng từ {@code @Tool} bọc ngoại lệ trong {@code ToolExecutionException}: tìm mã nghiệp vụ trong chuỗi nguyên nhân. */
    private static AppException appExceptionIn(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AppException app) {
                return app;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String error(String code) {
        return ToolResults.error(code);
    }

    /** {@code getPromptTokens()} của Spring AI đã gồm token đọc và ghi bộ đệm. */
    private static TokenUsage usageOf(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        log.debug("Token của lần gọi mô hình: vào {}, ra {}, đọc bộ đệm {}, ghi bộ đệm {}", usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens());
        return new TokenUsage(orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record Step(AssistantMessage output, TokenUsage usage, String stopReason, String model) {
    }

    /** Chữ của một vòng; {@code preamble}: viết cùng tin với lời gọi một công cụ khác {@code suggest_link}. */
    private record Said(String text, boolean preamble) {
    }
}
