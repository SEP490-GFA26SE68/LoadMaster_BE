package fu.se184491.loadmaster_be.service.assistant.loop;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Gọi mô hình cho một lượt. Bản này chưa có công cụ: một lần gọi. Lỗi mạng, hết giờ, lỗi của API đều thành
 * {@code ASSISTANT_UNAVAILABLE}; mô hình từ chối ({@code refusal}) thì trả câu từ chối cố định theo ngôn ngữ.
 */
@Slf4j
@Component
public class AssistantLoop {

    static final String REFUSAL = "refusal";
    static final String REFUSAL_VI = "Mình không thể giúp với yêu cầu này. Bạn có thể hỏi mình về cách dùng LoadMaster.";
    static final String REFUSAL_EN = "I can't help with that request. You can ask me about using LoadMaster.";

    public LoopResult run(ChatModel model, Prompt prompt, String locale) {
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            // Không ghi prompt hay chữ người dùng vào log
            log.warn("Gọi mô hình của trợ lý thất bại: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput() == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        TokenUsage usage = usageOf(response);
        String stopReason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        String modelName = response.getMetadata() == null ? null : response.getMetadata().getModel();
        if (REFUSAL.equalsIgnoreCase(stopReason)) {
            return new LoopResult(refusal(locale), usage, REFUSAL, modelName);
        }
        String text = result.getOutput().getText();
        return new LoopResult(text == null ? "" : text.strip(), usage,
                stopReason == null ? null : stopReason.toLowerCase(java.util.Locale.ROOT), modelName);
    }

    static String refusal(String locale) {
        return "en".equalsIgnoreCase(locale) ? REFUSAL_EN : REFUSAL_VI;
    }

    /** {@code getPromptTokens()} của Spring AI đã gồm token đọc và ghi bộ đệm. */
    private static TokenUsage usageOf(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        return new TokenUsage(orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
