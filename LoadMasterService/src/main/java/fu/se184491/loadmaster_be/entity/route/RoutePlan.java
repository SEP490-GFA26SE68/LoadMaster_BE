package fu.se184491.loadmaster_be.entity.route;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(
        name = "route_plans",
        indexes = {
                @Index(name = "idx_route_plans_trip_id", columnList = "trip_id"),
                @Index(name = "idx_route_plans_active", columnList = "trip_id, is_active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Builder.Default
    @Column(name = "plan_version", nullable = false)
    private Integer planVersion = 1;

    @Column(name = "total_distance_meters")
    private Integer totalDistanceMeters;

    @Column(name = "total_duration_seconds")
    private Integer totalDurationSeconds;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** Mã lần tối ưu (ba phương án A · B · C cùng một {@code run_id}). */
    @Column(name = "run_id", length = 36)
    private String runId;

    /** A | B | C; phương án cũ / tạo tay để trống. */
    @Column(name = "candidate_label", length = 1)
    private String candidateLabel;

    /** SHORTEST | AVOID_CONGESTION | DEADLINE_FIRST */
    @Column(name = "objective", length = 40)
    private String objective;

    @Builder.Default
    @Column(name = "is_approved", nullable = false)
    private Boolean approved = false;

    /** Thứ tự điểm và giờ đến đề xuất; chỉ ghi lên {@code delivery_stops} khi duyệt. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json", columnDefinition = "json")
    private Map<String, Object> proposalJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
