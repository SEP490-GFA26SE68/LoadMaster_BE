package fu.se184491.loadmaster_be.dto.response.subscription;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionCurrentResponse {
    private Long subscriptionId;
    private Long planId;
    private String planName;
    private SubscriptionTier tier;
    private SubscriptionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private Boolean autoRenew;
    private Integer creditsBalance;
    private Integer creditsMonthlyGrant;
    private AlgorithmTier algorithmTier;
}
