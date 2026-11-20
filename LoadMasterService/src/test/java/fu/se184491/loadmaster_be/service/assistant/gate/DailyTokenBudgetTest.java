package fu.se184491.loadmaster_be.service.assistant.gate;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyTokenBudgetTest {

    /** 20/11/2026 15:00 giờ Việt Nam. */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T08:00:00Z"));
    private final AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
    private final AssistantProperties properties = new AssistantProperties();

    private DailyTokenBudget budget() {
        return new DailyTokenBudget(messages, properties, clock);
    }

    @Test
    void spendingTheWholeBudgetBlocksTheNextCall() {
        when(messages.sumTokensSince(any())).thenReturn(0L);
        DailyTokenBudget budget = budget();
        budget.ensureAvailable();

        budget.record(2_000_000L);

        assertThat(budget.remaining()).isZero();
        assertThatThrownBy(budget::ensureAvailable)
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_BUDGET_EXCEEDED));
    }

    @Test
    void vietnamMidnightResetsTheCount() {
        when(messages.sumTokensSince(any())).thenReturn(0L);
        DailyTokenBudget budget = budget();
        budget.ensureAvailable();
        budget.record(2_000_000L);

        // 21/11/2026 00:00 giờ Việt Nam
        clock.set(Instant.parse("2026-11-20T17:00:00Z"));

        assertThatCode(budget::ensureAvailable).doesNotThrowAnyException();
        assertThat(budget.remaining()).isEqualTo(2_000_000L);
    }

    @Test
    void justBeforeVietnamMidnightIsStillTheSameDay() {
        when(messages.sumTokensSince(any())).thenReturn(0L);
        DailyTokenBudget budget = budget();
        budget.ensureAvailable();
        budget.record(2_000_000L);

        clock.set(Instant.parse("2026-11-20T16:59:59Z"));

        assertThatThrownBy(budget::ensureAvailable).isInstanceOf(AppException.class);
    }

    @Test
    void firstUseLoadsTodaysTotalFromTheDatabase() {
        // Đầu ngày 20/11 giờ Việt Nam = 19/11 17:00 UTC
        when(messages.sumTokensSince(Instant.parse("2026-11-19T17:00:00Z"))).thenReturn(500_000L);

        DailyTokenBudget budget = budget();

        assertThat(budget.remaining()).isEqualTo(1_500_000L);
        verify(messages).sumTokensSince(Instant.parse("2026-11-19T17:00:00Z"));
    }

    @Test
    void negativeTokenCountsAreIgnored() {
        when(messages.sumTokensSince(any())).thenReturn(0L);
        DailyTokenBudget budget = budget();

        budget.record(-50L);

        assertThat(budget.remaining()).isEqualTo(2_000_000L);
    }
}
