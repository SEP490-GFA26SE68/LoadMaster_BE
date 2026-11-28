package fu.se184491.loadmaster_be.service.monitoring;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Mốc giờ của giám sát (vị trí xe, ETA, hạn giao, sự cố). Cột trong DB không có múi giờ và lưu theo giờ Việt Nam, nên chuỗi gửi cho
 * web / mobile phải kèm offset +07:00 (ví dụ {@code 2026-11-28T21:00:05+07:00}). Trước đây gắn {@code Z} (UTC) làm màn giám sát hiện
 * giờ lệch 7 tiếng.
 */
public final class MonitoringTime {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private MonitoringTime() {
    }

    public static String toIso(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(VIETNAM).toOffsetDateTime().toString();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(VIETNAM);
    }
}
