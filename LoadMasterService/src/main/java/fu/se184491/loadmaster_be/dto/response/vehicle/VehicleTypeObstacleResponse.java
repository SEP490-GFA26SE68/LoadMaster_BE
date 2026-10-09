package fu.se184491.loadmaster_be.dto.response.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.ObstacleType;

public record VehicleTypeObstacleResponse(
        Long id,
        String name,
        ObstacleType type,
        Integer x,
        Integer y,
        Integer z,
        Integer length,
        Integer width,
        Integer height,
        Boolean loadBearing
) {
}