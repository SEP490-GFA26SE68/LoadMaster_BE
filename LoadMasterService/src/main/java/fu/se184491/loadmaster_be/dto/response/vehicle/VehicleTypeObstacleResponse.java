package fu.se184491.loadmaster_be.dto.response.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.ObstacleType;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record VehicleTypeObstacleResponse(
        Long id,
        String name,
        ObstacleType type,
        BigDecimal x,
        BigDecimal y,
        BigDecimal z,
        Integer length,
        Integer width,
        Integer height,
        Boolean loadBearing
) {
}