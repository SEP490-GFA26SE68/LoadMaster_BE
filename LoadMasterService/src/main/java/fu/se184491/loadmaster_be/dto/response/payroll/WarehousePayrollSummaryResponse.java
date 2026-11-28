package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

/** Một dòng của bảng lương kho. */
public record WarehousePayrollSummaryResponse(
        Long workerId,
        String workerName,
        BigDecimal baseSalaryVnd,
        int loadedPackages,
        BigDecimal loadedKg,
        BigDecimal loadedAmountVnd,
        boolean kpiAchieved,
        BigDecimal kpiBonusVnd,
        BigDecimal totalSalaryVnd
) {
}
