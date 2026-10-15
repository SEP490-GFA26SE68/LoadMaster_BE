package fu.se184491.loadmaster_be.entity.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionType;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_exceptions", indexes = {
        @Index(name = "idx_trip_exceptions_trip_id", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false)
    private TripExceptionType type;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Builder.Default
    @Column(name = "delay_minutes", nullable = false)
    private Integer delayMinutes = 0;

    @Builder.Default
    @ColumnDefault("'OPEN'")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TripExceptionStatus status = TripExceptionStatus.OPEN;

    @Column(name = "stop_number")
    private Integer stopNumber;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by")
    private User reportedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "escalation_reason", length = 40)
    private String escalationReason;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalated_by")
    private User escalatedBy;

    @PrePersist
    public void prePersist() {
        if (reportedAt == null) {
            reportedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TripExceptionStatus.OPEN;
        }
        if (delayMinutes == null) {
            delayMinutes = 0;
        }
    }
}
