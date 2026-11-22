package fu.se184491.loadmaster_be.dto.response.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyWarehousePayrollRateResponse(
        Long id,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd,
        BigDecimal rateVndPerKg,
        /** Chỉ tiêu KPI (kg/tháng); null là công ty không dùng KPI. */
        BigDecimal kpiTargetKg,
        BigDecimal kpiBonusVnd,
        /** Trạng thái bảng lương kho của chính tháng áp dụng; null khi tháng đó chưa tính. */
        PayrollPeriodStatus periodStatus
) {
}
