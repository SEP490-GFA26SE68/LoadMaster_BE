package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record PayrollTripLineResponse(
        Long tripId,
        String tripCode,
        BigDecimal planKg,
        BigDecimal sharePercent,
        BigDecimal allocatedKg,
        BigDecimal kgAmountVnd,
        BigDecimal handlingAmountVnd,
        BigDecimal totalAmountVnd
) {
}
