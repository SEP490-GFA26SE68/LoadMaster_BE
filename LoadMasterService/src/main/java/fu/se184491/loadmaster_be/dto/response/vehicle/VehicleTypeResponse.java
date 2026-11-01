
package fu.se184491.loadmaster_be.dto.response.vehicle;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record VehicleTypeResponse(
        Long id,
        String name,

        // Kích thước thùng xe (cm)
        Integer innerLength,
        Integer innerWidth,
        Integer innerHeight,

        // Kích thước cửa xe (cm)
        Integer doorWidth,
        Integer doorHeight,

        BigDecimal maxPayloadKg,
        BigDecimal frontAxleLimitKg,
        BigDecimal rearAxleLimitKg,
        BigDecimal maxCogOffsetRatio,
        Boolean hazardousCapable,

        List<VehicleTypeObstacleResponse> obstacles,

        Long vehicleCount
) {
}
