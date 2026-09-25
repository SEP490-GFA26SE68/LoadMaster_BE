package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "load_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoadPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id")
    private OptimizationJob job;

    @Column(name = "plan_name", length = 100)
    private String planName;

    @Column(name = "packed_items_count")
    private Integer packedItemsCount;

    @Column(name = "volume_utilization", precision = 5, scale = 2)
    private BigDecimal volumeUtilization;

    @Column(name = "weight_utilization", precision = 5, scale = 2)
    private BigDecimal weightUtilization;

    @Builder.Default @Column(name = "is_approved")
    private Boolean approved = false;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Builder.Default
    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "parent_plan_id")
    private Long parentPlanId;
}
