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

    private Long driverUserId;
}
