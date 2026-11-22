package fu.se184491.loadmaster_be.service.assistant.gate;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Lần dùng đầu tiên nạp tổng token hôm nay từ DB (khởi động lại không mất phần đã tiêu), sau đó cộng trong bộ nhớ. Sang
 * ngày mới theo giờ Việt Nam thì về 0.
 */
@Component
public class DailyTokenBudget implements AssistantBudget {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AssistantMessageRepository messages;
    private final AssistantProperties properties;
    private final Clock clock;

    private LocalDate day;
    private long used;

    @Autowired
    public DailyTokenBudget(AssistantMessageRepository messages, AssistantProperties properties) {
        this(messages, properties, Clock.systemUTC());
    }

    DailyTokenBudget(AssistantMessageRepository messages, AssistantProperties properties, Clock clock) {
        this.messages = messages;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public synchronized void ensureAvailable() {
        if (remaining() <= 0) {
            throw new AppException(ErrorCode.ASSISTANT_BUDGET_EXCEEDED);
        }
    }

    @Override
    public synchronized void record(long tokens) {
        roll();
        used += Math.max(0, tokens);
    }

    @Override
    public synchronized long remaining() {
        roll();
        return Math.max(0, properties.getDailyTokenBudget() - used);
    }

    private void roll() {
        LocalDate today = LocalDate.now(clock.withZone(VIETNAM));
        if (today.equals(day)) {
            return;
        }
        used = day == null ? messages.sumTokensSince(today.atStartOfDay(VIETNAM).toInstant()) : 0;
        day = today;
    }
}
