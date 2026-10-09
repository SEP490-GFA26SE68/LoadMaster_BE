package fu.se184491.loadmaster_be.dto.response.vehicle;
public record VehicleResponse(
        Long id,
        String name,
        String licensePlate,
        Long vehicleTypeId,
        String vehicleTypeName,
        Long companyId,
        String companyName
) {
}