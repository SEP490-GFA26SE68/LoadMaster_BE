package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DispatcherPayrollPeriodTest {

    @Test
    void defaultStatusIsDraftAndLockedAtIsNull() {
        DispatcherPayrollPeriod period = DispatcherPayrollPeriod.builder().build();
        assertEquals(PayrollPeriodStatus.DRAFT, period.getStatus());
        assertNull(period.getLockedAt());
    }
}
