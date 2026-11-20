package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DispatcherPayrollLineRequest(
        @NotNull @Min(0) Integer overtimeDays,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 0) BigDecimal kpiAmountVnd
) {
}
