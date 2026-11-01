package fu.se184491.loadmaster_be.dto.response.subscription;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractResponse {

    private Long id;

    private Long companyId;

    private String companyCode;

    private String companyName;

    private Long planId;

    private String planCode;

    private String planName;

    private SubscriptionTier planTier;

    private BigDecimal price;

    private BillingCycle billingCycle;

    private SubscriptionStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime expiresAt;

    private Boolean autoRenew;

    private String adminName;

    private String adminEmail;

    private String adminPhone;

    private Integer creditsGranted;
}
