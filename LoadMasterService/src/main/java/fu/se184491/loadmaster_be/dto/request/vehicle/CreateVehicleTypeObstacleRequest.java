package fu.se184491.loadmaster_be.dto.request.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.ObstacleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateVehicleTypeObstacleRequest(

        @NotBlank
        String name,

        @NotNull
        ObstacleType type,

        @NotNull
        @PositiveOrZero
        Integer x,

        @NotNull
        @PositiveOrZero
        Integer y,

        @NotNull
        @PositiveOrZero
        Integer z,

        @NotNull
        @Positive
        Integer length,

        @NotNull
        @Positive
        Integer width,

        @NotNull
        @Positive
        Integer height,

        Boolean loadBearing
) {
}