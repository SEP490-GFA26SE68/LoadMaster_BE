package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Sửa chuyến (PATCH): trường {@code null} là giữ nguyên.
 * Bỏ tài xế bằng {@code unassignDriver = true} (không gửi kèm {@code driverId}).
 */
public record TripUpdateRequest(

        @Size(max = 150)
        String tripName,

        LocalDateTime departureTime,

        @Positive
        Long vehicleId,

        @Positive
        Long driverId,

        Boolean unassignDriver,

        @Positive
        Long originWarehouseId

) {}
