package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record PayrollDriverSummaryResponse(
        Long driverId,
        String driverName,
        BigDecimal baseSalaryVnd,
        BigDecimal totalKg,
        BigDecimal kgAmountVnd,
        BigDecimal totalKm,
        BigDecimal kmAmountVnd,
        BigDecimal totalSalaryVnd
) {
}
