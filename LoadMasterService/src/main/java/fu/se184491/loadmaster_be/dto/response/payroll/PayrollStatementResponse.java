package fu.se184491.loadmaster_be.dto.response.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayrollStatementResponse(
        boolean calculated,
        String period,
        PayrollPeriodStatus status,
        Long driverId,
        String driverName,
        BigDecimal baseSalaryVnd,
        BigDecimal totalKg,
        BigDecimal rateVndPerKg,
        BigDecimal kgAmountVnd,
        BigDecimal commissionPercent,
        BigDecimal handlingFeeVndPerKg,
        BigDecimal handlingDriverPercent,
        BigDecimal handlingAmountVnd,
        /** Chỉ tiêu KPI (kg/tháng) của kỳ này; null khi công ty không dùng KPI hoặc bảng chưa chốt. */
        BigDecimal kpiTargetKg,
        /** Tài xế có đạt KPI không; null khi bảng chưa chốt. */
        Boolean kpiAchieved,
        /** Tiền thưởng KPI đã cộng vào lương (màn lương trên mobile đọc đúng tên này); null khi bảng chưa chốt. */
        BigDecimal kpiBonusVnd,
        /** Tiền phạt đã trừ vào lương; null khi bảng chưa chốt (tài xế xem). */
        BigDecimal penaltyVnd,
        /** Lý do phạt; null khi không phạt hoặc bảng chưa chốt. */
        String penaltyNote,
        BigDecimal totalSalaryVnd,
        List<PayrollTripLineResponse> trips
) {
}
