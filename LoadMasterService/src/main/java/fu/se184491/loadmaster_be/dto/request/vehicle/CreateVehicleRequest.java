package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateVehicleRequest(

        @NotBlank
        String name,

        @NotBlank
        String licensePlate,

        @NotNull
        Long vehicleTypeId
) {
}