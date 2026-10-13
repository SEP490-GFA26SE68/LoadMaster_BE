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

    private String packageCode;
    private String trackingBarcode;
    private String qrToken;

    private Integer actualLength;
    private Integer actualWidth;
    private Integer actualHeight;
    private BigDecimal actualWeightKg;

    private HandlingClass handlingClass;
    private String destination;
    private PackageStatus status;

    private Boolean isPinned;
}