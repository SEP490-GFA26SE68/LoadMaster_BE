package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record TripCreateRequest(

        @NotBlank(message = "Tên chuyến không được để trống")
        @Size(max = 150)
        String tripName,

        @NotNull(message = "Thời gian xuất phát không được để trống")
        LocalDateTime departureTime,

        @NotNull(message = "Vui lòng chọn xe")
        @Positive
        Long vehicleId,

        @Positive
        Long driverId,

        @NotNull(message = "Vui lòng chọn kho xuất phát")
        @Positive
        Long originWarehouseId

) {}
