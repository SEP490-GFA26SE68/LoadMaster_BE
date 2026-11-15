package fu.se184491.loadmaster_be.dto.response.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.util.List;

public record PayrollPeriodResponse(
        String period,
        PayrollPeriodStatus status,
        List<PayrollDriverSummaryResponse> drivers
) {
}
