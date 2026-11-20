package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.support.FakeChatModel;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BriefingServiceTest {

    // 21/11/2026 08:00 giờ Việt Nam
    private static final Instant T0 = Instant.parse("2026-11-21T01:00:00Z");
    private static final AssistantCaller DISPATCHER =
            new AssistantCaller(8L, 10L, UserRole.DISPATCHER, "Vận tải Long Bình", "Pro");
    private static final AssistantCaller MANAGER =
            new AssistantCaller(9L, 10L, UserRole.MANAGER, "Vận tải Long Bình", "Pro");

    private static final BriefingFacts QUIET = new BriefingFacts(Map.of(), 0, 0, 0, 0, 0L, 0L, 3000L, 750L, List.of());
    private static final BriefingFacts BUSY = new BriefingFacts(
            Map.of(TripStatus.IN_TRANSIT, 2L, TripStatus.PLANNED, 3L), 2, 1, 1, 4, 1L, 0L, 600L, 750L,
            List.of(new BriefingFacts.AttentionTrip("TRIP-LB-RUN", 1, 1, 2, 0, false, false),
                    new BriefingFacts.AttentionTrip("TRIP-LB-WAIT", 0, 0, 0, 0, true, false)));

    private final AssistantProperties properties = new AssistantProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final BriefingFactsCollector collector = mock(BriefingFactsCollector.class);
    private final List<String> rateKeys = new ArrayList<>();
    private final List<Long> recorded = new ArrayList<>();
    private final AssistantBudget budget = new AssistantBudget() {
        @Override
        public void ensureAvailable() {
        }

        @Override
        public void record(long tokens) {
            recorded.add(tokens);
        }

        @Override
        public long remaining() {
            return Long.MAX_VALUE;
        }
    };

    @BeforeEach
    void enable() {
        properties.setEnabled(true);
    }

    private BriefingService service(ChatModel model) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                model == null ? Map.<String, Object>of() : Map.<String, Object>of("assistantChatModel", model));
        ObjectProvider<ChatModel> provider = beans.getBeanProvider(ChatModel.class);
        AssistantRateLimiter limiter = (key, limit) -> rateKeys.add(key + "@" + limit);
        return new BriefingService(provider, properties, limiter, budget, collector, clock);
    }

    private static String bullets(String... lines) {
        StringBuilder json = new StringBuilder("{\"bullets\":[");
        for (int i = 0; i < lines.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(lines[i]).append('"');
        }
        return json.append("]}").toString();
    }

    @Test
    void quietDayIsTheFixedSentenceWithoutCallingTheModel() {
        when(collector.collect(10L, UserRole.DISPATCHER, LocalDate.of(2026, 11, 21))).thenReturn(QUIET);
        FakeChatModel model = new FakeChatModel();

        AssistantBriefingResponse vi = service(model).briefing(DISPATCHER, "vi");
        AssistantBriefingResponse en = service(model).briefing(DISPATCHER, "en");

        assertThat(vi.bullets()).containsExactly("Hôm nay không có việc cần chú ý.");
        assertThat(en.bullets()).containsExactly("Nothing needs your attention today.");
        assertThat(vi.links()).isEmpty();
        assertThat(vi.generatedAt()).isEqualTo(T0);
        assertThat(model.calls()).isZero();
        assertThat(recorded).isEmpty();
    }

    @Test
    void modelWritesFromFactsWithStructuredOutputAndTheBudgetCountsTokens() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().reply(bullets(
                "TRIP-LB-RUN có 1 sự cố đã chuyển lên quản lý công ty.",
                "2 điểm giao đã có cảnh báo nguy cơ trễ hạn.",
                "4 xác nhận tay đang chờ bạn duyệt."), 700, 90);

        AssistantBriefingResponse briefing = service(model).briefing(DISPATCHER, "vi");

        assertThat(briefing.bullets()).hasSize(3);
        assertThat(recorded).containsExactly(790L);
        assertThat(rateKeys).containsExactly("user:8@60");
        Prompt prompt = model.prompts().getFirst();
        AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
        assertThat(options.getOutputSchema()).contains("\"bullets\"");
        assertThat(options.getModel()).isEqualTo(FakeChatModel.MODEL);
        String turn = prompt.getInstructions().getLast().getText();
        assertThat(turn).startsWith("Ngôn ngữ: vi\n<SỰ KIỆN>\n").endsWith("\n</SỰ KIỆN>")
                .contains("\"role\":\"DISPATCHER\"", "\"date\":\"2026-11-21\"", "\"tripsTodayTotal\":5",
                        "\"tripCode\":\"TRIP-LB-RUN\"", "\"creditsLow\":true")
                .doesNotContain("Long Bình");
    }

    @Test
    void linksComeFromFactsAndTheCallersRole() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().answerAlways(bullets("2 điểm giao đã có cảnh báo nguy cơ trễ hạn."),
                10, 10);

        List<AssistantLink> links = service(model).briefing(DISPATCHER, "vi").links();

        assertThat(links).extracting(AssistantLink::to)
                .containsExactly("/giam-sat", "/chuyen/TRIP-LB-RUN", "/chuyen/TRIP-LB-WAIT", "/chuyen");
        assertThat(links.getFirst().label()).isEqualTo("Giám sát");
    }

    @Test
    void bulletWithANumberOrTripCodeNotInTheFactsIsDropped() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().reply(bullets(
                "Hôm nay có 5 chuyến, 2 chuyến đang vận chuyển.",
                "Khoảng 7 điểm giao có thể trễ.",
                "Chuyến TRIP-LB-999 cần xem lại.",
                "Tỉ lệ lấp đầy 87,4%.",
                "Số dư 600 credit đã xuống tới ngưỡng 750.",
                "- TRIP-LB-WAIT có phương án chờ duyệt.",
                "Xem https://loadmaster.vn/giam-sat ngay."), 10, 10);

        AssistantBriefingResponse briefing = service(model).briefing(DISPATCHER, "vi");

        assertThat(briefing.bullets()).containsExactly(
                "Hôm nay có 5 chuyến, 2 chuyến đang vận chuyển.",
                "Số dư 600 credit đã xuống tới ngưỡng 750.",
                "TRIP-LB-WAIT có phương án chờ duyệt.");
    }

    @Test
    void nothingLeftAfterFilteringIsUnavailableAndNotCached() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().reply(bullets("Có 99 việc."), 10, 10)
                .reply(bullets("Có 5 chuyến hôm nay."), 10, 10);
        BriefingService service = service(model);

        assertThatThrownBy(() -> service.briefing(DISPATCHER, "vi"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(service.briefing(DISPATCHER, "vi").bullets()).containsExactly("Có 5 chuyến hôm nay.");
        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    void refusalOrBrokenJsonIsUnavailable() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().reply(bullets("Có 5 chuyến hôm nay."), "refusal", 10, 10)
                .reply("{\"bullets\":[\"Có 5 chuyến\"]} thêm chữ", 10, 10);
        BriefingService service = service(model);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.briefing(DISPATCHER, "vi"))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
    }

    @Test
    void sameUserWithinTwoHoursReadsTheCachedBriefing() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().answerAlways(bullets("Có 5 chuyến hôm nay."), 10, 10);
        BriefingService service = service(model);

        AssistantBriefingResponse first = service.briefing(DISPATCHER, "vi");
        clock.set(T0.plus(Duration.ofMinutes(119)));
        AssistantBriefingResponse again = service.briefing(DISPATCHER, "vi");

        assertThat(again).isSameAs(first);
        assertThat(model.calls()).isEqualTo(1);
        verify(collector, times(1)).collect(anyLong(), any(), any());
        assertThat(rateKeys).hasSize(1);

        clock.set(T0.plus(Duration.ofHours(2)));
        AssistantBriefingResponse later = service.briefing(DISPATCHER, "vi");

        assertThat(model.calls()).isEqualTo(2);
        assertThat(later.generatedAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void cacheIsPerUserAndLanguage() {
        when(collector.collect(anyLong(), any(), any())).thenReturn(BUSY);
        FakeChatModel model = new FakeChatModel().answerAlways(bullets("Có 5 chuyến hôm nay."), 10, 10);
        BriefingService service = service(model);

        service.briefing(DISPATCHER, "vi");
        service.briefing(MANAGER, "vi");
        service.briefing(DISPATCHER, "en");

        assertThat(model.calls()).isEqualTo(3);
    }

    @Test
    void otherRolesAreForbiddenBeforeAnythingIsCounted() {
        FakeChatModel model = new FakeChatModel();
        AssistantCaller driver = new AssistantCaller(7L, 10L, UserRole.DRIVER, "Vận tải Long Bình", "Pro");
        AssistantCaller admin = new AssistantCaller(6L, 10L, UserRole.ADMIN, "Vận tải Long Bình", "Pro");

        for (AssistantCaller caller : List.of(driver, admin)) {
            assertThatThrownBy(() -> service(model).briefing(caller, "vi"))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
        verify(collector, never()).collect(anyLong(), any(), any());
        assertThat(rateKeys).isEmpty();
    }

    @Test
    void disabledAssistantIsUnavailable() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service(new FakeChatModel()).briefing(DISPATCHER, "vi"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        verify(collector, never()).collect(anyLong(), any(), any());
    }

    @Test
    void missingModelIsUnavailable() {
        assertThatThrownBy(() -> service(null).briefing(DISPATCHER, "vi"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        verify(collector, never()).collect(anyLong(), any(), any());
    }
}
