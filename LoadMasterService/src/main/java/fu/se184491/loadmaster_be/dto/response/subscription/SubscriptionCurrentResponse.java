package fu.se184491.loadmaster_be.dto.response.subscription;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;

    private Boolean autoRenew;
    private Integer creditsBalance;
    private Integer creditsMonthlyGrant;
    private AlgorithmTier algorithmTier;
}
