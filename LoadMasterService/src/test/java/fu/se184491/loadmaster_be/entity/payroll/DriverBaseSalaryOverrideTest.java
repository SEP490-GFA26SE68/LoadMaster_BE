package fu.se184491.loadmaster_be.entity.payroll;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DriverBaseSalaryOverrideTest {

    @Test
    void newOverrideRecordsVietnamLocalTimeWithoutFractionalSeconds() {
        ZoneId vietnam = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDateTime before = LocalDateTime.now(vietnam).truncatedTo(ChronoUnit.SECONDS);

        DriverBaseSalaryOverride override = DriverBaseSalaryOverride.builder().build();
        override.prePersist();

        LocalDateTime after = LocalDateTime.now(vietnam).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = assertInstanceOf(LocalDateTime.class, override.getCreatedAt());
        assertFalse(createdAt.isBefore(before));
        assertFalse(createdAt.isAfter(after));
        assertEquals(0, createdAt.getNano());
    }
}
