package fu.se184491.loadmaster_be.service.assistant.gate;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cửa sổ cố định một giờ, giữ trong bộ nhớ: khởi động lại là đếm lại, mỗi máy một bộ đếm. Đủ cho một máy chủ; nhiều máy
 * thì thay bằng cài đặt dùng chung sau interface {@link AssistantRateLimiter}.
 */
@Component
public class InMemoryRateLimiter implements AssistantRateLimiter {

    /** Quá số khoá này thì dọn các khoá của giờ trước. */
    private static final int PRUNE_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void check(String key, int limitPerHour) {
        long hour = clock.instant().getEpochSecond() / 3600;
        Window window = windows.compute(key, (k, current) -> current == null || current.hour() != hour
                ? new Window(hour, 1)
                : new Window(hour, current.count() + 1));
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.values().removeIf(w -> w.hour() < hour);
        }
        if (window.count() > limitPerHour) {
            throw new AppException(ErrorCode.ASSISTANT_RATE_LIMITED);
        }
    }

    private record Window(long hour, int count) {
    }
}
