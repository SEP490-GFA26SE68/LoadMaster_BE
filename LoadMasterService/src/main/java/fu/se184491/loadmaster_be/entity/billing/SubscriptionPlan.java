package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "subscription_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_code", length = 50, unique = true, nullable = false)
    private String planCode;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "price", precision = 15, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle")
    private BillingCycle billingCycle;

    @Column(name = "max_vehicles")
    private Integer maxVehicles;

    @Column(name = "max_monthly_jobs")
    private Integer maxMonthlyJobs;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 20, nullable = false)
    private SubscriptionTier tier = SubscriptionTier.BASIC;

    @Column(name = "monthly_credits")
    private Integer monthlyCredits;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm_tier", length = 20)
    private AlgorithmTier algorithmTier = AlgorithmTier.EP_DBLF;

    @Column(name = "features", columnDefinition = "TEXT")
    private String features;

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true;

    public BigDecimal getPriceVnd() {
        return price;
    }

    public void setPriceVnd(BigDecimal priceVnd) {
        this.price = priceVnd;
    }
}
