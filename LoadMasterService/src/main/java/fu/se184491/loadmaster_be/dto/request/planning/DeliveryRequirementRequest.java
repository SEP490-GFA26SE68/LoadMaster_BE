
package fu.se184491.loadmaster_be.dto.request.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRequirementRequest {

    @NotNull
    @Positive
    private Long customerId;

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

    @NotNull
    @Min(1)
    private Integer expectedPackageCount;

    @Size(max = 500)
    private String note;

    /**
     * Tạo yêu cầu không cần có kiện.
     * Warehouse sẽ bổ sung kiện sau khi tiếp nhận hàng.
     */
    @Builder.Default
    private List<@NotNull @Positive Long> packageIds = new ArrayList<>();
}
