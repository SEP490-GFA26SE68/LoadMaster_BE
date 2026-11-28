package fu.se184491.loadmaster_be.service.assistant.gate;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T03:10:00Z"));
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Test
    void twentyFirstCallOfTheSameKeyInOneHourIsRejected() {
        for (int i = 0; i < 20; i++) {
            limiter.check("ip:1.2.3.4", 20);
        }

        assertThatThrownBy(() -> limiter.check("ip:1.2.3.4", 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_RATE_LIMITED));
    }

    @Test
    void theNextHourStartsAFreshWindow() {
        for (int i = 0; i < 20; i++) {
            limiter.check("ip:1.2.3.4", 20);
        }
        clock.set(Instant.parse("2026-11-20T04:00:00Z"));

        assertThatCode(() -> limiter.check("ip:1.2.3.4", 20)).doesNotThrowAnyException();
    }

    @Test
    void keysAreCountedIndependently() {
        for (int i = 0; i < 20; i++) {
            limiter.check("ip:1.2.3.4", 20);
        }

        assertThatCode(() -> limiter.check("ip:5.6.7.8", 20)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("user:42", 60)).doesNotThrowAnyException();
    }
}
