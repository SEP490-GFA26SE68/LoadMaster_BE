package fu.se184491.loadmaster_be.dto.request.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import jakarta.validation.constraints.Future;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class UpdateDeliveryRequirementRequest {

    @Future
    private LocalDateTime deadline;

    private DeliveryRequirementPriority priority;
}
