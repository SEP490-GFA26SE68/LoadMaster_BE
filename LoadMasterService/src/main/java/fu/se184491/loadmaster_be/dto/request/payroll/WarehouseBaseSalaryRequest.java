package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lương cứng riêng của một nhân viên kho. {@code effectiveFrom} tuỳ chọn: bỏ trống là từ ngày 1 tháng sau (sớm nhất được phép);
 * có thì phải là ngày 1 của tháng sau trở đi, như lương cứng riêng của tài xế.
 */
public record WarehouseBaseSalaryRequest(
        LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal baseSalaryVnd
) {
}
