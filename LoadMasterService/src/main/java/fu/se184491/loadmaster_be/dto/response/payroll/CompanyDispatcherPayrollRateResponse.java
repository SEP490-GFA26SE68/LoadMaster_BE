package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyDispatcherPayrollRateResponse(
        Long id,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd,
        BigDecimal overtimeVndPerHour,
        BigDecimal overtimeHoursPerDay
) {
}
