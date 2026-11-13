package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateVehicleRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 50) String licensePlate,
        @NotNull Long vehicleTypeId
) {
}
