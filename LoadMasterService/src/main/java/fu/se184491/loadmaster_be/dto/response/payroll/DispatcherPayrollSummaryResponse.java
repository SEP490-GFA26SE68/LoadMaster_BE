package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record DispatcherPayrollSummaryResponse(
        Long dispatcherId,
        String dispatcherName,
        BigDecimal baseSalaryVnd,
        Integer overtimeDays,
        BigDecimal overtimeAmountVnd,
        BigDecimal kpiAmountVnd,
        BigDecimal totalSalaryVnd
) {
}
