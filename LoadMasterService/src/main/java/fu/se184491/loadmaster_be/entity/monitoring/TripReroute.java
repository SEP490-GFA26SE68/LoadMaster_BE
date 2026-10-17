package fu.se184491.loadmaster_be.entity.monitoring;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "trip_reroutes", indexes = {
        @Index(name = "idx_trip_reroutes_trip_id", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripReroute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "stop_number")
    private Integer stopNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json", columnDefinition = "json", nullable = false)
    private Map<String, Object> proposalJson;

    @Column(name = "selected_index")
    private Integer selectedIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_json", columnDefinition = "json")
    private Map<String, Object> confirmedJson;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private User confirmedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
