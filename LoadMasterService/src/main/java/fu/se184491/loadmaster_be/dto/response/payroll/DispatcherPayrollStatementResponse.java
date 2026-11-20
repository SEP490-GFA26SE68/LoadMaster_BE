package fu.se184491.loadmaster_be.dto.response.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatcherPayrollStatementResponse(
        boolean calculated,
        String period,
        PayrollPeriodStatus status,
        Long dispatcherId,
        String dispatcherName,
        BigDecimal baseSalaryVnd,
        BigDecimal overtimeVndPerHour,
        BigDecimal overtimeHoursPerDay,
        Integer overtimeDays,
        BigDecimal overtimeAmountVnd,
        BigDecimal kpiAmountVnd,
        BigDecimal totalSalaryVnd
) {
}
