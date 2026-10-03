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
    private String destination;
    private BigDecimal destinationLat;
    private BigDecimal destinationLng;
    private LocalDateTime deadline;
    private DeliveryRequirementPriority priority;
    private DeliveryRequirementStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private List<Long> packageIds;
}
