package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DispatcherBaseSalaryResponse(
        Long id,
        Long dispatcherId,
        String dispatcherName,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd
) {
}
