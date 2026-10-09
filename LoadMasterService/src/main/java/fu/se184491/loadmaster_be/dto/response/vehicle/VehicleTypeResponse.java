package fu.se184491.loadmaster_be.dto.response.vehicle;

import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeObstacleResponse;

import java.math.BigDecimal;
import java.util.List;

public record VehicleTypeResponse(
        Long id,
        String name,
        Integer innerLength,
        Integer innerWidth,
        Integer innerHeight,
        BigDecimal maxPayloadKg,
        BigDecimal frontAxleLimitKg,
        BigDecimal rearAxleLimitKg,
        BigDecimal maxCogOffsetRatio,
        Boolean hazardousCapable,
        List<VehicleTypeObstacleResponse> obstacles
) {
}