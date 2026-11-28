package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Tiền phạt quản lý công ty nhập tay cho một tài xế trong một tháng. {@code penaltyVnd = 0} là bỏ phạt.
 * Có phạt thì phải có lý do (kiểm ở service vì phụ thuộc số tiền).
 */
public record DriverPayrollPenaltyRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 0) BigDecimal penaltyVnd,
        @Size(max = 200) String note
) {
}
