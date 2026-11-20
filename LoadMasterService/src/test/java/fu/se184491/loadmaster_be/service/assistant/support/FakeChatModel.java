package fu.se184491.loadmaster_be.service.assistant.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Mô hình giả: trả lần lượt các câu đã xếp, ghi lại mọi prompt nhận được. Không gọi mạng. */
public final class FakeChatModel implements ChatModel {

    public static final String MODEL = "claude-haiku-5-5-fake";

    private final Deque<Supplier<ChatResponse>> replies = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private Supplier<ChatResponse> fallback;

    /** Hết câu đã xếp thì trả câu này mãi (test gọi qua HTTP nhiều lượt). */
    public FakeChatModel answerAlways(String text, int inputTokens, int outputTokens) {
        fallback = () -> response(text, "end_turn", inputTokens, outputTokens);
        return this;
    }

    public FakeChatModel reply(String text, int inputTokens, int outputTokens) {
        return reply(text, "end_turn", inputTokens, outputTokens);
    }

    public FakeChatModel reply(String text, String stopReason, int inputTokens, int outputTokens) {
        replies.addLast(() -> response(text, stopReason, inputTokens, outputTokens));
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

    public List<Prompt> prompts() {
        return prompts;
    }

    public int calls() {
        return prompts.size();
    }

    private static ChatResponse response(String text, String stopReason, int inputTokens, int outputTokens) {
        Generation generation = new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(stopReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(MODEL).usage(new DefaultUsage(inputTokens, outputTokens)).build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
