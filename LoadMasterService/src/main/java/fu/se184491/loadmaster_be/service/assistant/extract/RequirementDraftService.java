package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kỹ năng E: đọc nội dung điều phối viên dán vào (tin nhắn, email của khách) thành bản nháp yêu cầu giao. Máy chủ không
 * tạo bản ghi nào — trang dùng bản nháp để điền sẵn form, người dùng tự chọn khách và bấm lưu như bình thường.
 *
 * <p>Một lần gọi mô hình, không công cụ, đầu ra có cấu trúc ({@code outputSchema} → {@code output_config.format}, như
 * {@code try-extract}); {@link RequirementDraftValidator} kiểm lại sau mô hình, kể cả hạn giao phải ở tương lai theo
 * giờ Việt Nam. Cổng của người đăng nhập dùng chung với {@code /messages}: khoá {@code user:…}, giới hạn lượt mỗi giờ,
 * hạn mức token ngày. Không ghi nội dung dán vào DB hay log.
 */
@Slf4j
@Service
public class RequirementDraftService {

    static final String SYSTEM_PROMPT = "assistant/requirement-draft.md";
    static final String SCHEMA = "assistant/requirement-draft-schema.json";

    private static final String OPEN = "<VĂN BẢN DÁN VÀO>";
    private static final String CLOSE = "</VĂN BẢN DÁN VÀO>";
    // Lý do dừng mà chữ trả về không dùng được: bị từ chối hoặc bị cắt giữa chừng
    private static final Set<String> UNUSABLE_STOPS = Set.of("refusal", "max_tokens");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<DayOfWeek, String> WEEKDAYS = Map.of(
            DayOfWeek.MONDAY, "thứ Hai", DayOfWeek.TUESDAY, "thứ Ba", DayOfWeek.WEDNESDAY, "thứ Tư",
            DayOfWeek.THURSDAY, "thứ Năm", DayOfWeek.FRIDAY, "thứ Sáu", DayOfWeek.SATURDAY, "thứ Bảy",
            DayOfWeek.SUNDAY, "Chủ nhật");

    private final ObjectProvider<ChatModel> chatModels;
    private final AssistantProperties properties;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBudget budget;
    private final Clock clock;
    private final String systemText;
    private final String schema;

    @Autowired
    public RequirementDraftService(@Qualifier(AssistantModelConfig.MODEL_BEAN) ObjectProvider<ChatModel> chatModels,
                                   AssistantProperties properties, AssistantRateLimiter rateLimiter,
                                   AssistantBudget budget) {
        this(chatModels, properties, rateLimiter, budget, Clock.systemUTC());
    }

    RequirementDraftService(ObjectProvider<ChatModel> chatModels, AssistantProperties properties,
                            AssistantRateLimiter rateLimiter, AssistantBudget budget, Clock clock) {
        this.chatModels = chatModels;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.clock = clock;
        this.systemText = read(SYSTEM_PROMPT);
        this.schema = read(SCHEMA);
    }

    public RequirementDraftResult draft(String text, AssistantCaller caller) {
        ChatModel model = properties.isEnabled() ? chatModels.getIfAvailable() : null;
        if (model == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String pasted = text == null ? "" : text.strip();
        if (pasted.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        if (pasted.codePointCount(0, pasted.length()) > properties.getMaxDraftChars()) {
            throw new AppException(ErrorCode.ASSISTANT_MESSAGE_TOO_LONG);
        }
        rateLimiter.check("user:" + caller.userId(), properties.getUserPerHour());
        budget.ensureAvailable();

        // Một mốc giờ cho cả dòng "Hôm nay" gửi mô hình và phép kiểm hạn sau đó
        LocalDateTime now = LocalDateTime.now(clock.withZone(RequirementDraftValidator.VIETNAM))
                .truncatedTo(ChronoUnit.SECONDS);
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemText), new UserMessage(userText(pasted, now))),
                options(model));
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            // Không ghi prompt hay nội dung dán vào log
            log.warn("Gọi mô hình để đọc bản nháp yêu cầu giao thất bại: {}", e.getClass().getSimpleName());
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
            log.warn("Mô hình dừng khi đọc bản nháp yêu cầu giao: {}", stopReason);
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        try {
            return new RequirementDraftResult(RequirementDraftValidator.validate(result.getOutput().getText(), now),
                    inputTokens, outputTokens);
        } catch (AppException e) {
            log.warn("Mô hình trả JSON sai schema khi đọc bản nháp yêu cầu giao ({} token ra)", outputTokens);
            throw e;
        }
    }

    /**
     * Tuỳ chọn ở mức prompt thay hẳn tuỳ chọn mặc định của mô hình, nên dựng từ tuỳ chọn của chính bean (giữ model,
     * thinking, effort, bộ đệm) rồi thêm schema đầu ra và max_tokens riêng của lượt đọc bản nháp.
     */
    private AnthropicChatOptions options(ChatModel model) {
        AnthropicChatOptions.Builder builder = model.getOptions() instanceof AnthropicChatOptions defaults
                ? defaults.mutate()
                : AnthropicChatOptions.builder();
        builder.outputSchema(schema);
        builder.maxTokens(properties.getDraftMaxOutputTokens());
        return builder.build();
    }

    /**
     * Ngày giờ hiện tại để mô hình hiểu "mai", "thứ Sáu tuần này"; nội dung dán vào đi sau, bọc như dữ liệu, dấu ngoặc
     * nhọn đổi đi để chữ không đóng được khối.
     */
    private static String userText(String pasted, LocalDateTime now) {
        String data = pasted.replace('<', '‹').replace('>', '›');
        return "Hôm nay: " + DAY.format(now) + " (" + WEEKDAYS.get(now.getDayOfWeek()) + "), bây giờ "
                + TIME.format(now) + " giờ Việt Nam\n" + OPEN + "\n" + data + "\n" + CLOSE;
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
