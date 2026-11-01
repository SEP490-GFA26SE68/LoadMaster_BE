
package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CreateVehicleTypeRequest(

        @NotBlank
        String name,

        @NotNull
        @Positive
        Integer innerLength,

        @NotNull
        @Positive
        Integer innerWidth,

        @NotNull
        @Positive
        Integer innerHeight,

        // Kích thước cửa khoang hàng (cm)
        @Positive
        Integer doorWidth,

        @Positive
        Integer doorHeight,

        @NotNull
        @Positive
        BigDecimal maxPayloadKg,

        @Positive
        BigDecimal frontAxleLimitKg,

        @Positive
        BigDecimal rearAxleLimitKg,

        BigDecimal maxCogOffsetRatio,

        Boolean hazardousCapable,

        @Valid
        List<CreateVehicleTypeObstacleRequest> obstacles
) {
}
