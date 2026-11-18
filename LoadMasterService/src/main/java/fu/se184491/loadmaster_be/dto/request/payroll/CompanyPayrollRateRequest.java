package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyPayrollRateRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal baseSalaryVnd,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal rateVndPerKg,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal commissionPercent,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal handlingFeeVndPerKg,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal handlingDriverPercent
) {
}
