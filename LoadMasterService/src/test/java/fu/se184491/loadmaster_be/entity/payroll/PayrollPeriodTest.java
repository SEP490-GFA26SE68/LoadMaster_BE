package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PayrollPeriodTest {

    @Test
    void converterFormatsLockedAtToSecondsWithoutFractionalPart() {
        SupportTicket.LocalDateTimeSecondsConverter converter = new SupportTicket.LocalDateTimeSecondsConverter();
        LocalDateTime time = LocalDateTime.of(2026, 11, 16, 23, 45, 43, 347944000);

        String dbValue = converter.convertToDatabaseColumn(time);
        assertEquals("2026-11-16 23:45:43", dbValue);

        LocalDateTime roundTrip = converter.convertToEntityAttribute(dbValue);
        assertEquals(0, roundTrip.getNano());
        assertEquals(LocalDateTime.of(2026, 11, 16, 23, 45, 43), roundTrip);
    }

    @Test
    void converterHandlesNull() {
        SupportTicket.LocalDateTimeSecondsConverter converter = new SupportTicket.LocalDateTimeSecondsConverter();
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute("   "));
    }

    @Test
    void defaultStatusIsDraftAndLockedAtIsNull() {
        PayrollPeriod period = PayrollPeriod.builder().build();
        assertEquals(PayrollPeriodStatus.DRAFT, period.getStatus());
        assertNull(period.getLockedAt());
    }
}
