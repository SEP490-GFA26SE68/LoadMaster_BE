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

    // Thông tin định danh và liên kết
    private Long id;
    private Long orderId;
    private Long packageTypeId;

    private String packageCode;
    private String packageName;
    private String trackingBarcode;
    private String qrToken;

    // Kích thước và khối lượng
    private Integer actualLength;
    private Integer actualWidth;
    private Integer actualHeight;
    private BigDecimal actualWeightKg;

    // Thông tin giao hàng
    private HandlingClass handlingClass;
    private String destination;
    private String note;
    private PackageStatus status;
    private Boolean isPinned;

    // Thông số xếp hàng
    private String orientation;
    private Boolean uprightOnly;
    private Integer fragilityLevel;
    private Boolean stackable;
    private BigDecimal maxTopLoadKg;
    private Integer maxLayers;
    private BigDecimal minSupportRatio;
    private Integer priority;
    private Boolean mustLoad;
    private String packingGroup;
}
