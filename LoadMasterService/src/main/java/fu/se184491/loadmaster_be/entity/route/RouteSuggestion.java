package fu.se184491.loadmaster_be.entity.route;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "route_suggestions",
        indexes = {
                @Index(name = "idx_route_suggestions_leg_id", columnList = "route_leg_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_leg_id", nullable = false)
    private RouteLeg routeLeg;

    @Column(name = "trip_exception_id")
    private Long tripExceptionId;

    @Column(name = "suggestion_type", length = 30, nullable = false)
    private String suggestionType;

    @Column(name = "duration_diff_seconds")
    private Integer durationDiffSeconds;

    @Column(name = "distance_diff_meters")
    private Integer distanceDiffMeters;

    @Builder.Default
    @Column(name = "is_accepted", nullable = false)
    private Boolean isAccepted = false;

    @Column(name = "proposed_polyline", columnDefinition = "text")
    private String proposedPolyline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
