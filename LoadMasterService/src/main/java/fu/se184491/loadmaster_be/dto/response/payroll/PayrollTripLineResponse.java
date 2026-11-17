package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record PayrollTripLineResponse(
        Long tripId,
        String tripCode,
        BigDecimal planKg,
        BigDecimal planKm,
        BigDecimal sharePercent,
        BigDecimal allocatedKg,
        BigDecimal allocatedKm,
        BigDecimal kgAmountVnd,
        BigDecimal kmAmountVnd,
        BigDecimal totalAmountVnd
) {
}
