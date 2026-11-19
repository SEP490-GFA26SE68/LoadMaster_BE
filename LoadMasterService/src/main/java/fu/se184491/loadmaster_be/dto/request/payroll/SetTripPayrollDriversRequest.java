package fu.se184491.loadmaster_be.dto.request.payroll;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record SetTripPayrollDriversRequest(
        @NotEmpty List<@NotNull @Positive Long> driverIds
) {
}
