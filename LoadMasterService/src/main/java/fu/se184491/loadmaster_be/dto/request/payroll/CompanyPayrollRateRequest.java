package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyPayrollRateRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") BigDecimal baseSalaryVnd,
        @NotNull @DecimalMin("0.00") BigDecimal rateVndPerKg,
        @NotNull @DecimalMin("0.00") BigDecimal rateVndPerKm
) {
}
