package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.BillingCycle;
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
    @Column(name = "active")
    private Boolean active = true;
}
