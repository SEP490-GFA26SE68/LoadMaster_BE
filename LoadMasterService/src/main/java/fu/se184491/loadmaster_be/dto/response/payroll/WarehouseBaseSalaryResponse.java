package fu.se184491.loadmaster_be.dto.response.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WarehouseBaseSalaryResponse(
        Long id,
        Long workerId,
        String workerName,
        LocalDate effectiveFrom,
        BigDecimal baseSalaryVnd
) {
}
