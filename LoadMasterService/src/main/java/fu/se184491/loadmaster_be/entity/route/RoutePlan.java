package fu.se184491.loadmaster_be.entity.route;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
