package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;

import java.time.LocalDateTime;

/** Mức hạn của một giờ đến so với hạn — một luật cho màn Giám sát và màn tài xế. */
public final class DeadlineStatusRule {

    /** Sát hạn khi giờ đến trễ hơn hạn trừ ngần này phút. */
    public static final long AT_RISK_MARGIN_MINUTES = 30;

    private DeadlineStatusRule() {
    }

    /** {@code OK} khi giờ đến ≤ hạn − 30 phút; {@code AT_RISK} khi ≤ hạn; còn lại {@code MISSED}. Null khi thiếu một trong hai. */
    public static DeadlineStatus resolve(LocalDateTime arrival, LocalDateTime deadline) {
        if (arrival == null || deadline == null) {
            return null;
        }
        if (!arrival.isAfter(deadline.minusMinutes(AT_RISK_MARGIN_MINUTES))) {
            return DeadlineStatus.OK;
        }
        if (!arrival.isAfter(deadline)) {
            return DeadlineStatus.AT_RISK;
        }
        return DeadlineStatus.MISSED;
    }
}
