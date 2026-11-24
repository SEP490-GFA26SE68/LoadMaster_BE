package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantRouteStatsResponse;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.service.assistant.cache.GuestReplyCache;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.loop.AssistantLoop;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import fu.se184491.loadmaster_be.service.assistant.stats.RouteStatsService;
import fu.se184491.loadmaster_be.service.assistant.support.FakeChatModel;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import fu.se184491.loadmaster_be.service.assistant.tool.action.LinkTool;
import fu.se184491.loadmaster_be.service.assistant.tool.data.PricingPlansTool;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Bộ nhớ đệm câu trả lời lượt đầu của khách và bộ đếm MODEL / CACHED, trên H2 với mô hình giả. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class AssistantReplyCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final String GUEST_KEY = "guest-key-0123456789";
    private static final String QUESTION = "Giá gói Basic bao nhiêu?";

    @Autowired private AssistantConversationRepository conversations;
    @Autowired private AssistantMessageRepository messages;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AssistantPromptBuilder promptBuilder;
    @Autowired private AssistantLoop loop;
    @Autowired private AssistantToolCallRepository toolCalls;

    @MockitoBean private JwtDecoder jwtDecoder;

    private final FakeChatModel model = new FakeChatModel();
    private final AssistantProperties properties = new AssistantProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final List<Long> spent = new ArrayList<>();
    private final List<String> rateKeys = new ArrayList<>();
    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final SubscriptionPlan basic = SubscriptionPlan.builder().id(1L).planCode("BASIC").name("Basic")
            .price(new BigDecimal("1200000")).tier(SubscriptionTier.BASIC).monthlyCredits(3000).active(true).build();
    private boolean budgetExhausted;
    private int rateLimit = 100;
    private RouteStatsService stats;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(planRepository.findByActiveTrue()).thenAnswer(call -> List.of(basic));
        stats = new RouteStatsService(properties, null, clock);
    }

    private AssistantService service(AssistantTool... extra) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.<String, Object>of("assistantChatModel", (ChatModel) model));
        AssistantRateLimiter limiter = (key, limit) -> {
            rateKeys.add(key);
            if (rateKeys.size() > rateLimit) {
                throw new AppException(ErrorCode.ASSISTANT_RATE_LIMITED);
            }
        };
        AssistantBudget budget = new AssistantBudget() {
            @Override
            public void ensureAvailable() {
                if (budgetExhausted) {
                    throw new AppException(ErrorCode.ASSISTANT_BUDGET_EXCEEDED);
                }
            }

            @Override
            public void record(long tokens) {
                spent.add(tokens);
            }

            @Override
            public long remaining() {
                return budgetExhausted ? 0 : Long.MAX_VALUE;
            }
        };
        AssistantToolRegistry tools = new AssistantToolRegistry(List.of(extra), toolCalls, clock);
        GuestReplyCache cache = new GuestReplyCache(properties, planRepository, promptBuilder, clock);
        return new AssistantService(beans.getBeanProvider(ChatModel.class), properties, limiter, budget,
                promptBuilder, loop, tools, conversations, messages, transactionManager, clock, cache, stats);
    }

    private static AssistantAskCommand guest(String conversationId, String message) {
        return new AssistantAskCommand(conversationId, message, "vi", null, null, null, GUEST_KEY, "203.0.113.7",
                AssistantChannel.PUBLIC);
    }

    private static PricingPlansTool pricingTool() {
        SubscriptionPlanService plans = mock(SubscriptionPlanService.class);
        when(plans.getActivePlans()).thenReturn(List.of());
        return new PricingPlansTool(plans);
    }

    @Test
    void sameFirstQuestionIsAnsweredFromCacheWithoutModelOrBudget() {
        model.toolCall("get_pricing_plans", "{}", 900, 20)
                .textThenToolCall("Gói Basic 1.200.000 đ mỗi tháng.", "suggest_link", "{\"target\":\"PRICING\"}", 1000, 15)
                .reply("", 1100, 5);
        AssistantService service = service(pricingTool(), new LinkTool());

        AssistantReply first = service.ask(guest(null, QUESTION));
        budgetExhausted = true;
        AssistantReply second = service.ask(guest(null, "gia goi basic, bao nhieu"));

        assertThat(model.calls()).isEqualTo(3);
        assertThat(second.reply()).isEqualTo(first.reply()).isEqualTo("Gói Basic 1.200.000 đ mỗi tháng.");
        assertThat(second.links()).isEqualTo(first.links()).containsExactly(new AssistantLink("Bảng giá", "/bang-gia"));
        assertThat(second.usage()).isEqualTo(TokenUsage.ZERO);
        assertThat(second.handoff().offered()).isFalse();
        assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(spent).containsExactly(3040L);
        assertThat(rateKeys).containsExactly("ip:203.0.113.7", "ip:203.0.113.7");

        AssistantConversation replayed = conversations.findByPublicId(second.conversationId()).orElseThrow();
        assertThat(replayed.getGuestKey()).isEqualTo(GUEST_KEY);
        List<AssistantMessage> saved = messages.findByConversationIdOrderByIdAsc(replayed.getId());
        assertThat(saved).extracting(AssistantMessage::getRole)
                .containsExactly(AssistantMessageRole.USER, AssistantMessageRole.ASSISTANT);
        assertThat(saved.get(0).getContent()).isEqualTo("gia goi basic, bao nhieu");
        assertThat(saved.get(1).getContent()).isEqualTo(first.reply());
        assertThat(saved.get(1).getInputTokens()).isZero();
        assertThat(saved.get(1).getOutputTokens()).isZero();

        AssistantRouteStatsResponse counted = stats.stats(DAY, DAY);
        assertThat(counted.byRoute()).containsEntry("MODEL", 1L).containsEntry("CACHED", 1L);
        assertThat(counted.byTopic()).singleElement().satisfies(topic -> {
            assertThat(topic.topicId()).isEqualTo("plans");
            assertThat(topic.total()).isEqualTo(2);
        });
    }

    @Test
    void followUpOfACachedConversationGoesToTheModel() {
        model.answerAlways("Gói Basic 1.200.000 đ.", 900, 20);
        AssistantService service = service();

        AssistantReply first = service.ask(guest(null, QUESTION));
        service.ask(guest(null, QUESTION));
        service.ask(guest(first.conversationId(), QUESTION));

        assertThat(model.calls()).isEqualTo(2);
        assertThat(stats.stats(DAY, DAY).byRoute()).containsEntry("MODEL", 2L).containsEntry("CACHED", 1L);
    }

    @Test
    void repricingAPlanMissesTheCache() {
        model.answerAlways("Gói Basic 1.200.000 đ.", 900, 20);
        AssistantService service = service();

        service.ask(guest(null, QUESTION));
        basic.setPrice(new BigDecimal("1500000"));
        service.ask(guest(null, QUESTION));
        service.ask(guest(null, QUESTION));

        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    void refusalTruncatedAndFailedTurnsAreNotCached() {
        model.reply("x", "refusal", 900, 20)
                .reply("Câu trả lời bị cắt", "max_tokens", 900, 800)
                .fail(new RuntimeException("mạng lỗi"))
                .answerAlways("Gói Basic 1.200.000 đ.", 900, 20);
        AssistantService service = service();

        assertThat(service.ask(guest(null, QUESTION)).reply()).doesNotContain("Gói Basic");
        service.ask(guest(null, QUESTION));
        assertThatThrownBy(() -> service.ask(guest(null, QUESTION))).isInstanceOf(AppException.class);
        service.ask(guest(null, QUESTION));
        service.ask(guest(null, QUESTION));

        assertThat(model.calls()).isEqualTo(4);
    }

    @Test
    void handoffAndBrokenToolTurnsAreNotCached() {
        model.toolCall("offer_handoff", "{}", 900, 20).reply("Bạn muốn gặp nhân viên không?", 900, 20)
                .toolCall("offer_handoff", "{}", 900, 20).reply("Bạn muốn gặp nhân viên không?", 900, 20)
                .toolCall("broken_lookup", "{}", 900, 20).reply("Mình chưa tra được.", 900, 20)
                .toolCall("broken_lookup", "{}", 900, 20).reply("Mình chưa tra được.", 900, 20);
        AssistantService service = service(new GuestTools());

        AssistantReply offered = service.ask(guest(null, "Cho tôi gặp nhân viên"));
        service.ask(guest(null, "Cho tôi gặp nhân viên"));
        service.ask(guest(null, "Tra kiện PK-1"));
        service.ask(guest(null, "Tra kiện PK-1"));

        assertThat(offered.handoff().offered()).isTrue();
        assertThat(model.calls()).isEqualTo(8);
    }

    @Test
    void cachedAnswerStillCountsAgainstTheGuestRateLimit() {
        model.answerAlways("Gói Basic 1.200.000 đ.", 900, 20);
        rateLimit = 1;
        AssistantService service = service();

        service.ask(guest(null, QUESTION));
        assertThatThrownBy(() -> service.ask(guest(null, QUESTION)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_RATE_LIMITED));
        assertThat(model.calls()).isEqualTo(1);
        assertThat(stats.stats(DAY, DAY).byRoute()).containsEntry("CACHED", 0L);
    }

    @Test
    void cacheOffAlwaysAsksTheModel() {
        properties.getReplyCache().setEnabled(false);
        model.answerAlways("Gói Basic 1.200.000 đ.", 900, 20);
        AssistantService service = service();

        service.ask(guest(null, QUESTION));
        service.ask(guest(null, QUESTION));

        assertThat(model.calls()).isEqualTo(2);
    }

    /** Công cụ giả của khách: một cái mời chuyển nhân viên, một cái luôn lỗi. */
    public static final class GuestTools implements AssistantTool {
        @Override
        public Set<UserRole> roles() {
            return Set.of();
        }

        @Override
        public boolean guest() {
            return true;
        }

        @Tool(name = "offer_handoff", description = "Mời chuyển cho nhân viên")
        public String offerHandoff(ToolContext context) {
            AssistantTurn.of(context).offerHandoff();
            return "NEED_CONFIRMATION";
        }

        @Tool(name = "broken_lookup", description = "Tra cứu luôn lỗi")
        public String brokenLookup() {
            throw new IllegalStateException("hỏng");
        }
    }
}
