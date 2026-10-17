
package fu.se184491.loadmaster_be.dto.request.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class UpdateDeliveryRequirementRequest {

    @Positive
    private Long customerId;

    @Size(min = 1, max = 255)
    private String destination;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private BigDecimal destinationLat;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private BigDecimal destinationLng;

    @Future
    private LocalDateTime deadline;

    private DeliveryRequirementPriority priority;

    @Min(1)
    private Integer expectedPackageCount;

    @Size(max = 500)
    private String note;
}
