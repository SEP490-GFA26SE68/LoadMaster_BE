package fu.se184491.loadmaster_be.dto.request.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateContractRequest {

    private SubscriptionStatus status;

    private LocalDateTime expiresAt;

    private Boolean autoRenew;
}
