package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "load_plans", indexes = {
        @Index(name = "idx_load_plans_job_id", columnList = "job_id")
})
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

    @Column(name = "volume_utilization", precision = 7, scale = 4)
    private BigDecimal volumeUtilization;

    @Column(name = "weight_utilization", precision = 7, scale = 4)
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

    /**
     * Mục tiêu của phương án ứng viên (MAX_VOLUME | AXLE_BALANCE | MIN_REHANDLING) và nhãn (A | B | C).
     * Dịch vụ tối ưu ghi; phương án cũ và phương án của lần chạy lại để trống. Java khai để ddl-auto tạo cột.
     */
    @Column(name = "objective", length = 30)
    private String objective;

    @Column(name = "candidate_label", length = 1)
    private String candidateLabel;

    /**
     * Thuật toán đã tạo ra phương án này (EP_DBLF | GA | SA | EP_LUMO_R1_1 | LUMO_R1 | LUMO_R1_1 | LUMO_R2).
     * Dịch vụ tối ưu ghi; phương án cũ để trống và đọc theo thuật toán của job (mã của job là hạng của gói).
     */
    @Column(name = "algorithm_name", length = 60)
    private String algorithmName;

    /**
     * Bản duyệt dựng từ một phương án đã chỉnh tay (dời / xoay kiện). Dịch vụ tối ưu không ghi cột này: phương án của nó để trống (null).
     */
    @Column(name = "manually_edited")
    private Boolean manuallyEdited;

    /**
     * Phương án lỗi thời: hàng, xe hoặc điểm giao của chuyến đã đổi sau khi tối ưu. Không duyệt được; null là còn hiệu lực.
     */
    @Column(name = "is_stale")
    private Boolean stale;
}
