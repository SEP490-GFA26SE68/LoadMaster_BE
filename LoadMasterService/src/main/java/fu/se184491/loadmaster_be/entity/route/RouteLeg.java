package fu.se184491.loadmaster_be.entity.route;

import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(
        name = "route_legs",
        indexes = {
                @Index(name = "idx_route_legs_plan_id", columnList = "route_plan_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_route_plan_leg_seq",
                        columnNames = {"route_plan_id", "leg_sequence"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_plan_id", nullable = false)
    private RoutePlan routePlan;

    @Column(name = "leg_sequence", nullable = false)
    private Integer legSequence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_stop_id")
    private DeliveryStop startStop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "end_stop_id", nullable = false)
    private DeliveryStop endStop;

    @Column(name = "distance_meters", nullable = false)
    private Integer distanceMeters;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "polyline", columnDefinition = "text")
    private String polyline;

    /** Bước chỉ dẫn Goong (instruction, maneuver, toạ độ) — tài xế chỉ đường trong app. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps", columnDefinition = "json")
    private List<Map<String, Object>> steps;
}
