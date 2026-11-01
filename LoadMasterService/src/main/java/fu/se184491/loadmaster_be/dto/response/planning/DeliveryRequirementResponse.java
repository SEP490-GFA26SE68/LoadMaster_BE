
package fu.se184491.loadmaster_be.dto.response.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class DeliveryRequirementResponse {

    private Long id;
    private Long companyId;

    // Khách hàng gửi yêu cầu vận chuyển
    private Long customerId;
    private String customerName;

    // Thông tin giao hàng
    private String destination;
    private BigDecimal destinationLat;
    private BigDecimal destinationLng;
    private LocalDateTime deadline;
    private DeliveryRequirementPriority priority;

    // Thông tin tiếp nhận kiện
    private Integer expectedPackageCount;
    private Integer receivedPackageCount;
    private List<Long> packageIds;

    // Thông tin bổ sung
    private String note;
    private DeliveryRequirementStatus status;

    // Chuyến xe được gán (null nếu chưa gán)
    private Long tripId;
    private String tripCode;

    // Thông tin tạo
    private Long createdBy;
    private LocalDateTime createdAt;
}
