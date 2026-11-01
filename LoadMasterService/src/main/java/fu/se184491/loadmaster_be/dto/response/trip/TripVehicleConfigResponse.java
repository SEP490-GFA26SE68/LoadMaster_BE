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