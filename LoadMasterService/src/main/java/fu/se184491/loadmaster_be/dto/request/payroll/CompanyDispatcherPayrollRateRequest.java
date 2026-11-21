package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyDispatcherPayrollRateRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal baseSalaryVnd,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal overtimeVndPerHour,
        @NotNull @DecimalMin("0.00") @DecimalMax("24.00") @Digits(integer = 2, fraction = 2) BigDecimal overtimeHoursPerDay,
        @NotNull @Min(0) @Max(31) Integer overtimeDaysPerMonth,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal kpiAmountVnd
) {
}
