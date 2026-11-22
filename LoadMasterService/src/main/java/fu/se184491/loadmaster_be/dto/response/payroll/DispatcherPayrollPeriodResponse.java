package fu.se184491.loadmaster_be.dto.response.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;
import java.util.List;

public record DispatcherPayrollPeriodResponse(
        String period,
        PayrollPeriodStatus status,
        BigDecimal kpiAmountVnd,
        List<DispatcherPayrollSummaryResponse> dispatchers
) {
}
