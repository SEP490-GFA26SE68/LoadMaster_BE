package fu.se184491.loadmaster_be.dto.request.planning;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateTripRequest(
        @NotNull Long vehicleId,
        @NotNull Long driverId,
        @NotNull @Future LocalDateTime departureTime
) {
}
