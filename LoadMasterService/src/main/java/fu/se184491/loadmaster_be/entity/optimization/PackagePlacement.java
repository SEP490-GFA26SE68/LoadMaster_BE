package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;


import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "package_placements", uniqueConstraints = @UniqueConstraint(
        name = "uk_placement_plan_package", columnNames = {"load_plan_id", "package_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PackagePlacement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "load_plan_id")
    private LoadPlan loadPlan;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "package_id")
    private CargoPackage cargoPackage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stop_zone_id")
    private DeliveryStop stopZone;

    @Column(name = "pos_x") private Integer posX;
    @Column(name = "pos_y") private Integer posY;
    @Column(name = "pos_z") private Integer posZ;
    @Column(name = "packed_length") private Integer packedLength;
    @Column(name = "packed_width") private Integer packedWidth;
    @Column(name = "packed_height") private Integer packedHeight;

    @Min(0) @Max(5) @Column(name = "rotation_type")
    private Integer rotationType;

    @Column(name = "step_sequence")
    private Integer stepSequence;

    @Builder.Default @Column(name = "pinned")
    private Boolean pinned = false;

    /**
     * Lý do đặt (JSON array mã: ZONE_STOP_2, HEAVY_LOW, …) và số đo của chỗ đặt (V57). Dịch vụ tối ưu ghi khi cờ PLAN_SCORE_J
     * bật; Java chỉ đọc: ghim / bỏ ghim giữ nguyên, chỗ xếp của bản chỉnh tay để trống.
     */
    @Column(name = "reason_codes", columnDefinition = "jsonb", insertable = false, updatable = false)
    private String reasonCodes;

    /** Áp lực trên mặt trên của kiện [kg/m²] (H7); 0 khi không có gì đè. */
    @Column(name = "stack_pressure_kg_m2", precision = 16, scale = 3, insertable = false, updatable = false)
    private BigDecimal stackPressureKgM2;

    /** σ dùng khi kiểm H7 [kg/m²]; trống = kiện không nhận tải. */
    @Column(name = "top_load_capacity_kg_m2", precision = 16, scale = 3, insertable = false, updatable = false)
    private BigDecimal topLoadCapacityKgM2;

    /** Diện tích đáy được đỡ / diện tích đáy (H8); 1 trên sàn. */
    @Column(name = "support_ratio", precision = 5, scale = 4, insertable = false, updatable = false)
    private BigDecimal supportRatio;

    /** Khoảng cách từ mặt sau kiện tới cửa [cm]. */
    @Column(name = "distance_to_door_cm", precision = 10, scale = 3, insertable = false, updatable = false)
    private BigDecimal distanceToDoorCm;

    public Long getStopZoneId() {
        return stopZone != null ? stopZone.getId() : null;
    }
}
