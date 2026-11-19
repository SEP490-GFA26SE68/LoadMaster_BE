package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;

public record TripPayrollDriverResponse(
        Long driverId,
        String driverName,
        BigDecimal sharePercent
) {
}
