package fu.se184491.loadmaster_be.dto.request.trip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
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

        // Có thể chưa phân công tài xế lúc tạo chuyến.
        @Positive
        Long driverId,

        @NotBlank(message = "Tên kho xuất phát không được để trống")
        @Size(max = 150)
        String originName,

        @Size(max = 255)
        String originAddress,

        @NotNull(message = "Vui lòng chọn vĩ độ kho")
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        BigDecimal originLatitude,

        @NotNull(message = "Vui lòng chọn kinh độ kho")
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        BigDecimal originLongitude
) {}
