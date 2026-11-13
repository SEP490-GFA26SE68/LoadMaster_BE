package fu.se184491.loadmaster_be.dto.response.vehicle;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record VehicleResponse(
        Long id,
        String name,
        String licensePlate,
        Long vehicleTypeId,
        String vehicleTypeName,
        Long companyId,
        String companyName,
        VehicleStatus status,
        /** Ghi chú và lúc vào bảo dưỡng — chỉ khi xe đang bảo dưỡng. */
        String maintenanceNote,
        LocalDateTime maintenanceSince,
        /** Chuyến đang dùng xe (Đang xếp hàng / Đang vận chuyển) — chỉ khi xe đang phục vụ chuyến. */
        Long currentTripId,
        String currentTripCode,
        TripStatus currentTripStatus
) {
}
