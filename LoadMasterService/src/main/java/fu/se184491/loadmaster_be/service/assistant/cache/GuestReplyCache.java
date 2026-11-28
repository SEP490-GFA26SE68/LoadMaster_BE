package fu.se184491.loadmaster_be.service.assistant.cache;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Câu trả lời lượt đầu của khách ở trang công khai, giữ trong bộ nhớ của một máy chủ (khởi động lại là rỗng). Chỉ lượt
 * không có hội thoại cũ, không ngữ cảnh trang hay khung 3D mới dùng đệm: câu trả lời của nó chỉ phụ thuộc câu hỏi,
 * ngôn ngữ, ngày (prompt mang "Hôm nay"), danh mục gói đang bán và kiến thức trong prompt — đúng những thứ nằm trong
 * khoá. Đổi giá gói hay chữ của prompt thì khoá đổi, mục cũ không bao giờ khớp lại và tự rơi khỏi đệm theo TTL / LRU.
 */
@Component
public class GuestReplyCache {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Câu trả lời đã lưu. {@code topicId} là chủ đề máy chủ suy được lúc trả lời, hoặc {@code null}. */
    public record Entry(String text, List<AssistantLink> links, String model, String topicId) {
        public Entry {
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    private record Cached(Entry entry, Instant expiresAt) {
    }

    private final AssistantProperties properties;
    private final SubscriptionPlanRepository plans;
    private final AssistantPromptBuilder promptBuilder;
    private final Clock clock;
    private final Map<String, Cached> entries;
    private volatile String promptHash;

    @Autowired
    public GuestReplyCache(AssistantProperties properties, SubscriptionPlanRepository plans,
                           AssistantPromptBuilder promptBuilder) {
        this(properties, plans, promptBuilder, Clock.systemUTC());
    }

    public GuestReplyCache(AssistantProperties properties, SubscriptionPlanRepository plans,
                           AssistantPromptBuilder promptBuilder, Clock clock) {
        this.properties = properties;
        this.plans = plans;
        this.promptBuilder = promptBuilder;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > Math.max(1, properties.getReplyCache().getMaxEntries());
            }
        };
    }

    /**
     * Khoá đệm của lượt, hoặc rỗng khi lượt không được dùng đệm (đệm tắt, người đăng nhập, kênh ứng dụng, đã có hội
     * thoại, có ngữ cảnh trang / khung 3D, câu hỏi chỉ có dấu câu). Đọc danh mục gói đang bán (một truy vấn).
     */
    public Optional<String> keyFor(AssistantAskCommand command, String question, String locale) {
        if (!properties.getReplyCache().isEnabled() || command.channel() != AssistantChannel.PUBLIC
                || command.caller() != null || command.page() != null || command.viewer() != null
                || StringUtils.hasText(command.conversationId())) {
            return Optional.empty();
        }
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(VIETNAM));
        return Optional.of(sha256(String.join("\n", "guest-reply-v1", properties.getReplyCache().getVersion(),
                promptHash(), Objects.toString(properties.getModel(), ""), locale, today.toString(),
                planFingerprint(), normalized)));
    }

    public synchronized Optional<Entry> get(String key) {
        Cached cached = entries.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(cached.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached.entry());
    }

    public synchronized void put(String key, Entry entry) {
        Duration ttl = Duration.ofHours(Math.max(1, properties.getReplyCache().getTtlHours()));
        entries.put(key, new Cached(entry, clock.instant().plus(ttl)));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Bỏ dấu (cả "đ"), chữ thường, mọi chuỗi ký tự không phải chữ / số thành một dấu cách. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return NOT_WORD.matcher(plain).replaceAll(" ").strip();
    }

    /** Dấu của prompt khách đọc (luật + kiến thức): sửa file kiến thức là đệm cũ hết hiệu lực. */
    private String promptHash() {
        String hash = promptHash;
        if (hash == null) {
            hash = sha256(promptBuilder.systemText(AssistantChannel.PUBLIC));
            promptHash = hash;
        }
        return hash;
    }

    /** Dấu của mọi số công cụ giá có thể đưa cho mô hình: gói đang bán và luật credit. */
    private String planFingerprint() {
        StringBuilder out = new StringBuilder();
        plans.findByActiveTrue().stream()
                .sorted(Comparator.comparing(SubscriptionPlan::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(plan -> out.append(plan.getId()).append('|').append(plan.getPlanCode()).append('|')
                        .append(plan.getName()).append('|').append(plain(plan.getPrice())).append('|')
                        .append(plan.getBillingCycle()).append('|').append(plan.getTier()).append('|')
                        .append(plan.getMonthlyCredits()).append('|').append(plan.getAlgorithmTier()).append('|')
                        .append(plan.getFeatures()).append('\n'));
        out.append(CreditServiceImpl.INITIAL_OPTIMIZATION_CREDIT_COST).append('|')
                .append(CreditServiceImpl.MIN_TOPUP_CREDITS);
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            out.append('|').append(tier).append('=').append(plain(CreditServiceImpl.pricePerCredit(tier)));
        }
        return sha256(out.toString());
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Máy Java thiếu SHA-256", e);
        }
    }
}
