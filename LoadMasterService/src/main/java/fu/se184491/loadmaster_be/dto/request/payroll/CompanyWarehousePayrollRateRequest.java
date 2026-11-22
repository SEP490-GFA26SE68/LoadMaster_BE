package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Đơn giá lương kho của công ty cho một tháng áp dụng. KPI tuỳ chọn: {@code kpiTargetKg} rỗng là không dùng KPI; có chỉ tiêu thì phải có
 * {@code kpiBonusVnd} lớn hơn 0 (kiểm ở service, báo lỗi riêng).
 */
public record CompanyWarehousePayrollRateRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal baseSalaryVnd,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal rateVndPerKg,
        @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal kpiTargetKg,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal kpiBonusVnd
) {

    /** Đơn giá không dùng KPI. */
    public CompanyWarehousePayrollRateRequest(LocalDate effectiveFrom, BigDecimal baseSalaryVnd, BigDecimal rateVndPerKg) {
        this(effectiveFrom, baseSalaryVnd, rateVndPerKg, null, null);
    }
}
