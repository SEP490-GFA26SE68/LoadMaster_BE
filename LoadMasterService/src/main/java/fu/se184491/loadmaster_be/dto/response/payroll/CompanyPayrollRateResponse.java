package fu.se184491.loadmaster_be.dto.response.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompanyPayrollRateResponse(
        Long id,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd,
        BigDecimal rateVndPerKg,
        BigDecimal commissionPercent,
        BigDecimal handlingFeeVndPerKg,
        BigDecimal handlingDriverPercent,
        PayrollPeriodStatus periodStatus
) {
}
