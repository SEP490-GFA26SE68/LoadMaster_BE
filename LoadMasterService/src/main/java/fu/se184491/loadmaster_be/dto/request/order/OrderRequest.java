package fu.se184491.loadmaster_be.dto.request.order;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
    @NotNull(message = "Customer ID is required")
    private Long customerId;

    private Long deliveryStopId;
}
