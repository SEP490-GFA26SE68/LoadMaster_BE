package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record PayrollDriverSummaryResponse(
        Long driverId,
        String driverName,
        BigDecimal baseSalaryVnd,
        BigDecimal totalKg,
        BigDecimal kgAmountVnd,
        BigDecimal handlingAmountVnd,
        /** Đạt chỉ tiêu KPI tháng này (hệ thống tự tính từ tổng kg). */
        boolean kpiAchieved,
        /** Tiền thưởng KPI đã cộng vào lương (0 khi không đạt hoặc công ty không dùng KPI). */
        BigDecimal kpiAmountVnd,
        /** Tiền phạt quản lý nhập tay (0 khi không phạt). */
        BigDecimal penaltyVnd,
        /** Lý do phạt; null khi không phạt. */
        String penaltyNote,
        BigDecimal totalSalaryVnd
) {
}
