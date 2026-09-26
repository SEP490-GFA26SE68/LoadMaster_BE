package fu.se184491.loadmaster_be.dto.response.vehicle;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleResponse {
    private Long id;
    private Long vehicleTypeId;
    private String licensePlate;
    private BigDecimal frontAxleLimitKg;
    private BigDecimal rearAxleLimitKg;
    private Long companyId;
    private Long driverUserId;
}
