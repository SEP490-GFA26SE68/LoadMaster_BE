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

    @Column(name = "cog_x", precision = 10, scale = 3)
    private BigDecimal cogX;

    @Column(name = "cog_y", precision = 10, scale = 3)
    private BigDecimal cogY;

    @Column(name = "cog_z", precision = 10, scale = 3)
    private BigDecimal cogZ;

    @Column(name = "front_axle_load", precision = 10, scale = 2)
    private BigDecimal frontAxleLoad;

    @Column(name = "rear_axle_load", precision = 10, scale = 2)
    private BigDecimal rearAxleLoad;

    @Builder.Default
    @Column(name = "rehandling_count")
    private Integer rehandlingCount = 0;
}
