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
        BigDecimal totalKm,
        BigDecimal rateVndPerKm,
        BigDecimal kmAmountVnd,
        BigDecimal totalSalaryVnd,
        List<PayrollTripLineResponse> trips
) {
}
