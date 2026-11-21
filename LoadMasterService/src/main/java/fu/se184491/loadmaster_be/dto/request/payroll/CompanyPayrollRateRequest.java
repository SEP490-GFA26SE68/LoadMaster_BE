package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mức lương của công ty cho một tháng áp dụng. KPI tuỳ chọn: {@code kpiTargetKg} rỗng là không dùng KPI; có chỉ tiêu thì phải có
 * {@code kpiBonusVnd} lớn hơn 0 (kiểm ở service, báo lỗi riêng).
 */
public record CompanyPayrollRateRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal baseSalaryVnd,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal rateVndPerKg,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal commissionPercent,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal handlingFeeVndPerKg,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal handlingDriverPercent,
        @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal kpiTargetKg,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal kpiBonusVnd
) {

    /** Mức lương không dùng KPI. */
    public CompanyPayrollRateRequest(
            LocalDate effectiveFrom,
            BigDecimal baseSalaryVnd,
            BigDecimal rateVndPerKg,
            BigDecimal commissionPercent,
            BigDecimal handlingFeeVndPerKg,
            BigDecimal handlingDriverPercent
    ) {
        this(effectiveFrom, baseSalaryVnd, rateVndPerKg, commissionPercent, handlingFeeVndPerKg, handlingDriverPercent, null, null);
    }
}
