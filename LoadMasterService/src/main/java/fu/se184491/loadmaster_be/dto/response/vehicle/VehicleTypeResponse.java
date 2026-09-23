package fu.se184491.loadmaster_be.dto.response.vehicle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleTypeResponse {
    private Long id;
    private String name;
    private Integer innerLength;
    private Integer innerWidth;
    private Integer innerHeight;
    private BigDecimal maxPayloadKg;
    private Integer doorWidth;
    private Integer doorHeight;
    private Long companyId;
}
