package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleRequest {

    @NotNull(message = "Loại xe không được để trống")
    private Long vehicleTypeId;

    @NotBlank(message = "Biển số xe không được để trống")
    private String licensePlate;

    @NotNull(message = "Tải trọng trục trước không được để trống")
    @DecimalMin(value = "0.01", message = "Tải trọng trục trước phải lớn hơn 0")
    private BigDecimal frontAxleLimitKg;

    @NotNull(message = "Tải trọng trục sau không được để trống")
    @DecimalMin(value = "0.01", message = "Tải trọng trục sau phải lớn hơn 0")
    private BigDecimal rearAxleLimitKg;

    private Long driverUserId;
}
