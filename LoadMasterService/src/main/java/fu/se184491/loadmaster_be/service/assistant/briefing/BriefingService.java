package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bản tin "Tóm tắt hôm nay" của điều phối viên và quản lý công ty. Mọi số do {@link BriefingFactsCollector} đếm; mô hình
 * chỉ viết câu (một lần gọi, đầu ra có cấu trúc, không công cụ); câu có số hay mã chuyến lạ bị bỏ; liên kết do
 * {@link BriefingLinks} dựng theo quyền. Ngày không có việc gì: câu cố định, không gọi mô hình. Bản tin giữ 2 giờ cho
 * mỗi người dùng.
 */
@Slf4j
@Service
public class BriefingService {

    static final String SYSTEM_PROMPT = "assistant/briefing.md";
    static final String SCHEMA = "assistant/briefing-schema.json";
    static final Duration CACHE_TTL = Duration.ofHours(2);
    static final int CACHE_ENTRIES = 5000;
    static final String QUIET_VI = "Hôm nay không có việc cần chú ý.";
    static final String QUIET_EN = "Nothing needs your attention today.";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<UserRole> READERS = EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    private static final String OPEN = "<SỰ KIỆN>";
    private static final String CLOSE = "</SỰ KIỆN>";
    // Lý do dừng mà chữ trả về không dùng được: bị từ chối hoặc bị cắt giữa chừng
    private static final Set<String> UNUSABLE_STOPS = Set.of("refusal", "max_tokens");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final ObjectProvider<ChatModel> chatModels;
    private final AssistantProperties properties;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBudget budget;
    private final BriefingFactsCollector collector;
    private final Clock clock;
    private final BriefingCache cache;
    private final String systemText;
    private final String schema;

    @Autowired
    public BriefingService(@Qualifier(AssistantModelConfig.MODEL_BEAN) ObjectProvider<ChatModel> chatModels,
                           AssistantProperties properties, AssistantRateLimiter rateLimiter, AssistantBudget budget,
                           BriefingFactsCollector collector) {
        this(chatModels, properties, rateLimiter, budget, collector, Clock.systemUTC());
    }

    BriefingService(ObjectProvider<ChatModel> chatModels, AssistantProperties properties,
                    AssistantRateLimiter rateLimiter, AssistantBudget budget, BriefingFactsCollector collector,
                    Clock clock) {
        this.chatModels = chatModels;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.collector = collector;
        this.clock = clock;
        this.cache = new BriefingCache(CACHE_TTL, CACHE_ENTRIES, clock);
        this.systemText = read(SYSTEM_PROMPT);
        this.schema = read(SCHEMA);
    }

    public AssistantBriefingResponse briefing(AssistantCaller caller, String locale) {
        if (caller == null || caller.companyId() == null || !READERS.contains(caller.role())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        ChatModel model = properties.isEnabled() ? chatModels.getIfAvailable() : null;
        if (model == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String language = AssistantPromptBuilder.normalizeLocale(locale);
        String key = caller.userId() + ":" + caller.role() + ":" + language;
        var cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        rateLimiter.check("user:" + caller.userId(), properties.getUserPerHour());
        LocalDate today = LocalDate.ofInstant(clock.instant(), VIETNAM);
        BriefingFacts facts = collector.collect(caller.companyId(), caller.role(), today);
        List<String> bullets = facts.quiet()
                ? List.of("en".equals(language) ? QUIET_EN : QUIET_VI)
                : write(model, facts, caller.role(), language, today);
        AssistantBriefingResponse briefing = new AssistantBriefingResponse(clock.instant(), bullets,
                BriefingLinks.of(facts, caller.role(), language));
        cache.put(key, briefing);
        return briefing;
    }

    private List<String> write(ChatModel model, BriefingFacts facts, UserRole role, String language, LocalDate today) {
        budget.ensureAvailable();
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemText),
                new UserMessage(userText(facts, role, language, today))), options(model));
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            log.warn("Gọi mô hình để viết bản tin thất bại: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }

        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            // getPromptTokens() của Spring AI đã gồm token đọc và ghi bộ đệm
            long tokens = (long) orZero(usage.getPromptTokens()) + orZero(usage.getCompletionTokens());
            if (tokens > 0) {
                budget.record(tokens);
            }
        }

        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput() == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String stopReason = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        if (stopReason != null && UNUSABLE_STOPS.contains(stopReason.toLowerCase(Locale.ROOT))) {
            log.warn("Mô hình dừng khi viết bản tin: {}", stopReason);
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        List<String> kept = BriefingBullets.keep(bulletsOf(result.getOutput().getText()), facts);
        if (kept.isEmpty()) {
            log.warn("Bản tin không còn gạch đầu dòng nào sau khi lọc số");
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        return kept;
    }

    /** Đọc {@code {"bullets":[...]}}; sai dạng thì xem như mô hình không trả được bản tin. */
    private static List<String> bulletsOf(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text == null ? "" : text);
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        JsonNode bullets = root == null ? null : root.get("bullets");
        if (bullets == null || !bullets.isArray()) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        List<String> all = new ArrayList<>();
        for (JsonNode bullet : bullets) {
            if (bullet.isString()) {
                all.add(bullet.asString());
            }
        }
        return all;
    }

    /**
     * Tuỳ chọn ở mức prompt thay hẳn tuỳ chọn mặc định của mô hình, nên dựng từ tuỳ chọn của chính bean (giữ model,
     * thinking, effort, bộ đệm) rồi thêm schema đầu ra.
     */
    private AnthropicChatOptions options(ChatModel model) {
        AnthropicChatOptions.Builder builder = model.getOptions() instanceof AnthropicChatOptions defaults
                ? defaults.mutate()
                : AnthropicChatOptions.builder();
        builder.outputSchema(schema);
        builder.maxTokens(properties.getMaxOutputTokens());
        return builder.build();
    }

    /** Sự kiện đi trong lượt người dùng, bọc như dữ liệu; dấu ngoặc nhọn đổi đi để chữ không đóng được khối. */
    static String userText(BriefingFacts facts, UserRole role, String language, LocalDate today) {
        String data = BriefingFactsJson.of(facts, role, today).replace('<', '‹').replace('>', '›');
        return "Ngôn ngữ: " + language + "\n" + OPEN + "\n" + data + "\n" + CLOSE;
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
