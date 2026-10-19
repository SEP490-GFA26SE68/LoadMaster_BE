package fu.se184491.loadmaster_be.entity.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "eta_risk_alerts", indexes = {
        @Index(name = "idx_eta_risk_alerts_trip_id", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtaRiskAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "stop_number", nullable = false)
    private Integer stopNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DeadlineStatus status;

    @Column(name = "eta", nullable = false)
    private LocalDateTime eta;

    @Column(name = "deadline", nullable = false)
    private LocalDateTime deadline;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
