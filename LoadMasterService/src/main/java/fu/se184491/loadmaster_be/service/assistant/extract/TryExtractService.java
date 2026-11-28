package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Đọc mô tả hàng bằng lời của khách thành giá trị cho form "Thử với hàng của bạn" (trang giới thiệu).
 *
 * <p>Một lần gọi mô hình, không công cụ, đầu ra có cấu trúc: {@code outputSchema} của Spring AI gửi
 * {@code output_config.format} (JSON schema, {@code additionalProperties: false} ở mọi object) nên mô hình chỉ viết
 * được JSON đúng schema. Giới hạn của form không đặt được trong schema (structured outputs không nhận
 * {@code minimum}/{@code maximum}/{@code maxItems}) nên {@link TryExtractValidator} kiểm lại sau mô hình.
 *
 * <p>Cổng của khách dùng chung với {@code /public/messages}: cùng khoá {@code ip:…} và giới hạn lượt mỗi giờ, cùng
 * hạn mức token ngày. Không ghi mô tả của khách vào DB hay log.
 */
@Slf4j
@Service
public class TryExtractService {

    static final String SYSTEM_PROMPT = "assistant/try-extract.md";
    static final String SCHEMA = "assistant/try-extract-schema.json";

    private static final String OPEN = "<MÔ TẢ CỦA KHÁCH>";
    private static final String CLOSE = "</MÔ TẢ CỦA KHÁCH>";
    // Lý do dừng mà chữ trả về không dùng được: bị từ chối hoặc bị cắt giữa chừng
    private static final Set<String> UNUSABLE_STOPS = Set.of("refusal", "max_tokens");

    private final ObjectProvider<ChatModel> chatModels;
    private final AssistantProperties properties;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBudget budget;
    private final String systemText;
    private final String schema;

    public TryExtractService(@Qualifier(AssistantModelConfig.MODEL_BEAN) ObjectProvider<ChatModel> chatModels,
                             AssistantProperties properties, AssistantRateLimiter rateLimiter, AssistantBudget budget) {
        this.chatModels = chatModels;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.systemText = read(SYSTEM_PROMPT);
        this.schema = read(SCHEMA);
    }

    public TryExtractResult extract(String text, String locale, String clientIp) {
        ChatModel model = properties.isEnabled() ? chatModels.getIfAvailable() : null;
        if (model == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String description = text == null ? "" : text.strip();
        if (description.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        if (description.codePointCount(0, description.length()) > properties.getMaxMessageChars()) {
            throw new AppException(ErrorCode.ASSISTANT_MESSAGE_TOO_LONG);
        }
        rateLimiter.check("ip:" + Objects.requireNonNullElse(clientIp, "unknown"), properties.getGuestPerHour());
        budget.ensureAvailable();

        Prompt prompt = new Prompt(List.of(new SystemMessage(systemText),
                new UserMessage(userText(description, AssistantPromptBuilder.normalizeLocale(locale)))),
                options(model));
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            // Không ghi prompt hay mô tả của khách vào log
            log.warn("Gọi mô hình để đọc mô tả hàng thất bại: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }

        int inputTokens = 0;
        int outputTokens = 0;
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            // getPromptTokens() của Spring AI đã gồm token đọc và ghi bộ đệm
            inputTokens = orZero(usage.getPromptTokens());
            outputTokens = orZero(usage.getCompletionTokens());
        }
        if (inputTokens + outputTokens > 0) {
            budget.record((long) inputTokens + outputTokens);
        }

        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput() == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String stopReason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        if (stopReason != null && UNUSABLE_STOPS.contains(stopReason.toLowerCase(Locale.ROOT))) {
            log.warn("Mô hình dừng khi đọc mô tả hàng: {}", stopReason);
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        try {
            return new TryExtractResult(TryExtractValidator.validate(result.getOutput().getText()), inputTokens,
                    outputTokens);
        } catch (AppException e) {
            log.warn("Mô hình trả JSON sai schema khi đọc mô tả hàng ({} token ra)", outputTokens);
            throw e;
        }
    }

    /**
     * Tuỳ chọn ở mức prompt thay hẳn tuỳ chọn mặc định của mô hình, nên dựng từ tuỳ chọn của chính bean (giữ model,
     * thinking, effort, bộ đệm) rồi thêm schema đầu ra và max_tokens riêng của lượt trích xuất.
     */
    private AnthropicChatOptions options(ChatModel model) {
        AnthropicChatOptions.Builder builder = model.getOptions() instanceof AnthropicChatOptions defaults
                ? defaults.mutate()
                : AnthropicChatOptions.builder();
        builder.outputSchema(schema);
        builder.maxTokens(properties.getExtractMaxOutputTokens());
        return builder.build();
    }

    /** Mô tả của khách đi trong lượt người dùng, bọc như dữ liệu; dấu ngoặc nhọn đổi đi để chữ không đóng được khối. */
    private static String userText(String description, String locale) {
        String data = description.replace('<', '‹').replace('>', '›');
        return "Ngôn ngữ: " + locale + "\n" + OPEN + "\n" + data + "\n" + CLOSE;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Thiếu tài nguyên của trợ lý: " + path, e);
        }
    }
}
