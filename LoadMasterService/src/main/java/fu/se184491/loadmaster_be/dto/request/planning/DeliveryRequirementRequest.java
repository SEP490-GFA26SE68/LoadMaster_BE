package fu.se184491.loadmaster_be.dto.request.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRequirementRequest {

    @NotBlank
    @Size(max = 255)
    private String destination;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private BigDecimal destinationLat;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private BigDecimal destinationLng;

    @NotNull
    @Future
    private LocalDateTime deadline;

    @NotNull
    private DeliveryRequirementPriority priority;

    @NotEmpty
    private List<@NotNull Long> packageIds;
}
