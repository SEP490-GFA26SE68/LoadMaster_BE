package fu.se184491.loadmaster_be.dto.response.cargo;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageResponse {
    private Long id;
    private Long orderId;
    private Long packageTypeId;
    private String trackingBarcode;
    private Integer actualLength;
    private BigDecimal actualWeightKg;
    private Boolean isPinned;
    private String packageCode;
    private String qrToken;
    private HandlingClass handlingClass;
    private PackageStatus status;
}
