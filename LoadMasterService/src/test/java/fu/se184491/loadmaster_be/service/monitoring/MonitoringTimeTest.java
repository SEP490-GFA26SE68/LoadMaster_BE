package fu.se184491.loadmaster_be.service.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mốc giờ của giám sát (vị trí xe, ETA, sự cố) lưu ở cột không múi giờ theo giờ Việt Nam. Chuỗi gửi cho web và mobile phải mang
 * đúng offset +07:00; gắn nhầm `Z` (UTC) làm màn giám sát hiện giờ lệch 7 tiếng.
 */
class MonitoringTimeTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final TimeZone originalDefault = TimeZone.getDefault();

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalDefault);
    }

    @Test
    void toIsoReadsTheStoredValueAsVietnamTime() {
        String iso = MonitoringTime.toIso(LocalDateTime.of(2026, 11, 28, 21, 0, 5));

        // 21:00:05 giờ Việt Nam là 14:00:05 UTC.
        assertThat(OffsetDateTime.parse(iso).toInstant()).isEqualTo(Instant.parse("2026-11-28T14:00:05Z"));
        assertThat(OffsetDateTime.parse(iso).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 11, 28, 21, 0, 5));
    }

    @Test
    void nowIsVietnamTimeWhateverTheJvmDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        LocalDateTime now = MonitoringTime.now();

        assertThat(Duration.between(now, LocalDateTime.now(VIETNAM)).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void nullStaysNull() {
        assertThat(MonitoringTime.toIso(null)).isNull();
    }
}
