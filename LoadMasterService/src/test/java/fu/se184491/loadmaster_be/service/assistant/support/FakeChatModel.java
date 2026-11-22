package fu.se184491.loadmaster_be.service.assistant.support;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Mô hình giả: trả lần lượt các câu đã xếp, ghi lại mọi prompt nhận được. Không gọi mạng. */
public final class FakeChatModel implements ChatModel {

    public static final String MODEL = "claude-haiku-5-5-fake";
    public static final int MAX_TOKENS = 800;

    private final Deque<Supplier<ChatResponse>> replies = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private Supplier<ChatResponse> fallback;

    /** Hết câu đã xếp thì trả câu này mãi (test gọi qua HTTP nhiều lượt). */
    public FakeChatModel answerAlways(String text, int inputTokens, int outputTokens) {
        fallback = () -> response(new AssistantMessage(text), "end_turn", inputTokens, outputTokens);
        return this;
    }

    public FakeChatModel reply(String text, int inputTokens, int outputTokens) {
        return reply(text, "end_turn", inputTokens, outputTokens);
    }

    public FakeChatModel reply(String text, String stopReason, int inputTokens, int outputTokens) {
        replies.addLast(() -> response(new AssistantMessage(text), stopReason, inputTokens, outputTokens));
        return this;
    }

    /** Mô hình đòi gọi một công cụ. */
    public FakeChatModel toolCall(String tool, String arguments, int inputTokens, int outputTokens) {
        return toolCall(tool, arguments, "tool_use", inputTokens, outputTokens);
    }

    /** Mô hình đòi gọi công cụ với lý do dừng tuỳ ý (ví dụ {@code max_tokens}: tham số có thể bị cắt dở). */
    public FakeChatModel toolCall(String tool, String arguments, String stopReason, int inputTokens, int outputTokens) {
        replies.addLast(() -> toolUse(tool, arguments, stopReason, inputTokens, outputTokens));
        return this;
    }

    /** Mô hình viết chữ rồi đòi gọi công cụ trong cùng một tin (mô hình thật hay viết câu trả lời rồi gọi suggest_link). */
    public FakeChatModel textThenToolCall(String text, String tool, String arguments, int inputTokens, int outputTokens) {
        replies.addLast(() -> response(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall("toolu_" + (prompts.size() - 1), "function", tool,
                        arguments)))
                .build(), "tool_use", inputTokens, outputTokens));
        return this;
    }

    /** Hết câu đã xếp thì đòi công cụ mãi: thử xem vòng gọi có dừng không. */
    public FakeChatModel toolCallAlways(String tool, String arguments, int inputTokens, int outputTokens) {
        fallback = () -> toolUse(tool, arguments, "tool_use", inputTokens, outputTokens);
        return this;
    }

    public FakeChatModel fail(RuntimeException error) {
        replies.addLast(() -> {
            throw error;
        });
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (!replies.isEmpty()) {
            return replies.removeFirst().get();
        }
        if (fallback != null) {
            return fallback.get();
        }
        throw new IllegalStateException("FakeChatModel: chưa xếp câu trả lời nào");
    }

    /** Tuỳ chọn mặc định như bean thật: vòng gọi công cụ phải giữ chúng khi thêm công cụ. */
    @Override
    public ChatOptions getOptions() {
        return AnthropicChatOptions.builder().model(MODEL).maxTokens(MAX_TOKENS).build();
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    public int calls() {
        return prompts.size();
    }

    private ChatResponse toolUse(String tool, String arguments, String stopReason, int inputTokens, int outputTokens) {
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("toolu_" + (prompts.size() - 1), "function", tool, arguments)))
                .build();
        return response(output, stopReason, inputTokens, outputTokens);
    }

    private static ChatResponse response(AssistantMessage output, String stopReason, int inputTokens, int outputTokens) {
        Generation generation = new Generation(output, ChatGenerationMetadata.builder().finishReason(stopReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(MODEL).usage(new DefaultUsage(inputTokens, outputTokens)).build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
