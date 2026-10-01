package fu.se184491.loadmaster_be.dto.response.subscription;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanResponse {
    private Long id;
    private String planCode;
    private String name;
    private BigDecimal priceVnd;
    private SubscriptionTier tier;
    private Integer monthlyCredits;
    private AlgorithmTier algorithmTier;
    private String features;
    private BillingCycle billingCycle;
    private Integer maxVehicles;
    private Integer maxMonthlyJobs;
    private Boolean active;
}
