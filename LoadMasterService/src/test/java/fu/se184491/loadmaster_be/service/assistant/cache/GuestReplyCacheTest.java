package fu.se184491.loadmaster_be.service.assistant.cache;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.model.PageContext;
import fu.se184491.loadmaster_be.service.assistant.model.ViewerContext;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Khoá đệm của lượt đầu của khách: chuẩn hoá câu hỏi, những gì làm khoá đổi, TTL và trần số mục. */
class GuestReplyCacheTest {

    // 03:00Z là 10:00 giờ Việt Nam
    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final String GUEST_KEY = "guest-key-0123456789";
    private static final AssistantPromptBuilder PROMPT = new AssistantPromptBuilder();

    private final AssistantProperties properties = new AssistantProperties();
    private final SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
    private final MutableClock clock = new MutableClock(T0);
    private final List<SubscriptionPlan> activePlans = new ArrayList<>();
    private GuestReplyCache cache;

    @BeforeEach
    void setUp() {
        activePlans.add(SubscriptionPlan.builder().id(1L).planCode("BASIC").name("Basic")
                .price(new BigDecimal("1200000")).billingCycle(BillingCycle.MONTHLY).tier(SubscriptionTier.BASIC)
                .monthlyCredits(3000).active(true).build());
        when(plans.findByActiveTrue()).thenAnswer(call -> List.copyOf(activePlans));
        cache = new GuestReplyCache(properties, plans, PROMPT, clock);
    }

    private static AssistantAskCommand guest(String message, String conversationId) {
        return new AssistantAskCommand(conversationId, message, "vi", null, null, null, GUEST_KEY, "203.0.113.7",
                AssistantChannel.PUBLIC);
    }

    private String key(String message) {
        return cache.keyFor(guest(message, null), message, "vi").orElseThrow();
    }

    @Test
    void questionIsComparedWithoutMarksCaseOrPunctuation() {
        assertThat(GuestReplyCache.normalize("  Giá gói   PRO bao nhiêu?? ")).isEqualTo("gia goi pro bao nhieu");
        assertThat(GuestReplyCache.normalize("Đăng ký dùng thử!")).isEqualTo("dang ky dung thu");
        assertThat(key("Giá gói Pro bao nhiêu?")).isEqualTo(key("gia goi pro, bao nhieu"));
        assertThat(key("Giá gói Pro bao nhiêu?")).isNotEqualTo(key("Giá gói Basic bao nhiêu?"));
    }

    @Test
    void onlyAFirstGuestTurnWithoutPageContextHasAKey() {
        String q = "LoadMaster là gì?";
        assertThat(cache.keyFor(guest(q, "c_abcdefgh12"), q, "vi")).isEmpty();
        assertThat(cache.keyFor(new AssistantAskCommand(null, q, "vi", new PageContext("/gioi-thieu", null), null,
                null, GUEST_KEY, "203.0.113.7", AssistantChannel.PUBLIC), q, "vi")).isEmpty();
        assertThat(cache.keyFor(new AssistantAskCommand(null, q, "vi", null, new ViewerContext("TRIP-1", null, null),
                null, GUEST_KEY, "203.0.113.7", AssistantChannel.PUBLIC), q, "vi")).isEmpty();
        AssistantCaller dispatcher = new AssistantCaller(8L, 1L, UserRole.DISPATCHER, "Long Bình", "Pro");
        assertThat(cache.keyFor(new AssistantAskCommand(null, q, "vi", null, null, dispatcher, null, "10.0.0.5",
                AssistantChannel.APP), q, "vi")).isEmpty();
        assertThat(cache.keyFor(guest("?!...", null), "?!...", "vi")).isEmpty();

        properties.getReplyCache().setEnabled(false);
        assertThat(cache.keyFor(guest(q, null), q, "vi")).isEmpty();
    }

    @Test
    void localeDayPlanCatalogueAndVersionAreInTheKey() {
        String q = "Giá gói Basic?";
        String before = key(q);
        assertThat(cache.keyFor(guest(q, null), q, "en").orElseThrow()).isNotEqualTo(before);

        activePlans.getFirst().setPrice(new BigDecimal("1500000"));
        String repriced = key(q);
        assertThat(repriced).isNotEqualTo(before);

        activePlans.add(SubscriptionPlan.builder().id(2L).planCode("PRO").name("Pro").price(new BigDecimal("3500000"))
                .tier(SubscriptionTier.PRO).monthlyCredits(10000).active(true).build());
        assertThat(key(q)).isNotEqualTo(repriced);

        String withPro = key(q);
        properties.getReplyCache().setVersion("2");
        assertThat(key(q)).isNotEqualTo(withPro);

        String v2 = key(q);
        // 17:00Z là 00:00 hôm sau theo giờ Việt Nam
        clock.set(Instant.parse("2026-11-20T17:00:00Z"));
        assertThat(key(q)).isNotEqualTo(v2);
    }

    @Test
    void entriesExpireAfterTtl() {
        GuestReplyCache.Entry entry = new GuestReplyCache.Entry("Gói Basic 1.200.000 đ.",
                List.of(new AssistantLink("Bảng giá", "/bang-gia")), "claude", "plans");
        cache.put("k", entry);
        clock.set(T0.plus(Duration.ofHours(23)));
        assertThat(cache.get("k")).contains(entry);
        clock.set(T0.plus(Duration.ofHours(24)));
        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void leastRecentlyUsedEntryLeavesWhenFull() {
        properties.getReplyCache().setMaxEntries(2);
        GuestReplyCache.Entry entry = new GuestReplyCache.Entry("x", List.of(), "claude", null);
        cache.put("a", entry);
        cache.put("b", entry);
        cache.get("a");
        cache.put("c", entry);

        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("c")).isPresent();
    }
}
