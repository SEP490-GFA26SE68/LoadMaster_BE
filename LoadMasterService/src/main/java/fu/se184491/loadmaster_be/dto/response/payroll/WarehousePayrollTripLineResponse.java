package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Việc xếp của một nhân viên kho trên một chuyến trong kỳ. */
public record WarehousePayrollTripLineResponse(
        Long tripId,
        String tripCode,
        LocalDate loadedDate,
        int loadedPackages,
        BigDecimal loadedKg
) {
}
