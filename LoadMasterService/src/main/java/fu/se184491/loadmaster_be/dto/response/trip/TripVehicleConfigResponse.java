package fu.se184491.loadmaster_be.dto.response.trip;

import java.math.BigDecimal;
import java.util.List;

public record TripVehicleConfigResponse(
        Long vehicleId,
        String vehicleName,
        String licensePlate,

        Long vehicleTypeId,
        String vehicleTypeName,

        Integer innerLength,
        Integer innerWidth,
        Integer innerHeight,

        Integer doorWidth,
        Integer doorHeight,

        BigDecimal maxPayloadKg,
        BigDecimal frontAxleLimitKg,
        BigDecimal rearAxleLimitKg,
        BigDecimal maxCogOffsetRatio,

        BigDecimal frontAxleX,
        BigDecimal rearAxleX,
        BigDecimal emptyFrontAxleLoadKg,
        BigDecimal emptyRearAxleLoadKg,

        List<Obstacle> obstacles
) {
    /** Cấu hình của một xe (loại xe đang gắn: kích thước, cửa, giới hạn, trục, vật cản). */
    public static TripVehicleConfigResponse of(fu.se184491.loadmaster_be.entity.vehicle.Vehicle vehicle) {
        fu.se184491.loadmaster_be.entity.vehicle.VehicleType type = vehicle.getVehicleType();
        List<Obstacle> obstacles = type.getObstacles() == null ? List.of() : type.getObstacles().stream()
                .map(o -> new Obstacle(o.getId(), o.getName(), o.getType() == null ? null : o.getType().name(),
                        o.getX(), o.getY(), o.getZ(), o.getLength(), o.getWidth(), o.getHeight(), o.getLoadBearing()))
                .toList();
        return new TripVehicleConfigResponse(vehicle.getId(), vehicle.getName(), vehicle.getLicensePlate(),
                type.getId(), type.getName(), type.getInnerLength(), type.getInnerWidth(), type.getInnerHeight(),
                type.getDoorWidth(), type.getDoorHeight(), type.getMaxPayloadKg(), type.getFrontAxleLimitKg(),
                type.getRearAxleLimitKg(), type.getMaxCogOffsetRatio(), type.getFrontAxleX(), type.getRearAxleX(),
                type.getEmptyFrontAxleLoadKg(), type.getEmptyRearAxleLoadKg(), obstacles);
    }

    public record Obstacle(
            Long id,
            String name,
            String type,
            BigDecimal x,
            BigDecimal y,
            BigDecimal z,
            Integer length,
            Integer width,
            Integer height,
            Boolean loadBearing
    ) {}
}