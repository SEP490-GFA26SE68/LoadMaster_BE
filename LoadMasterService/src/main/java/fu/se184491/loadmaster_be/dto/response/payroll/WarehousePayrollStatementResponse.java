package fu.se184491.loadmaster_be.dto.response.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;
import java.util.List;

/** Phiếu lương kho của một nhân viên. Trường số rỗng (không có trong JSON) khi bảng chưa tính, hoặc nhân viên xem bảng chưa chốt. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WarehousePayrollStatementResponse(
        boolean calculated,
        String period,
        PayrollPeriodStatus status,
        Long workerId,
        String workerName,
        BigDecimal baseSalaryVnd,
        Integer loadedPackages,
        BigDecimal loadedKg,
        BigDecimal rateVndPerKg,
        BigDecimal loadedAmountVnd,
        /** Chỉ tiêu KPI (kg/tháng) của kỳ; null khi công ty không dùng KPI hoặc bảng chưa chốt. */
        BigDecimal kpiTargetKg,
        Boolean kpiAchieved,
        BigDecimal kpiBonusVnd,
        BigDecimal totalSalaryVnd,
        List<WarehousePayrollTripLineResponse> trips
) {
}
