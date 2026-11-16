package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DriverBaseSalaryResponse(
        Long id,
        Long driverId,
        String driverName,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd
) {
}
