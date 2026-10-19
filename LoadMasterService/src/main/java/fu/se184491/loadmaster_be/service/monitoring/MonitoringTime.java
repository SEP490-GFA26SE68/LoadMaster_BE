package fu.se184491.loadmaster_be.service.monitoring;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class MonitoringTime {

    private MonitoringTime() {
    }

    public static String toIso(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atOffset(ZoneOffset.UTC).toString();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
