package fu.se184491.loadmaster_be.dto.response.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import lombok.Builder;

@Builder
public record VehicleResponse(
        Long id,
        String name,
        String licensePlate,
        Long vehicleTypeId,
        String vehicleTypeName,
        Long companyId,
        String companyName,
        VehicleStatus status
) {
}