package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Phiếu xin nhận chuyến của tài xế.
 */
@Entity
@Table(name = "trip_claims", indexes = {
        @Index(name = "idx_trip_claims_trip_id", columnList = "trip_id"),
        @Index(name = "idx_trip_claims_driver_user_id", columnList = "driver_user_id"),
        @Index(name = "idx_trip_claims_company_id", columnList = "company_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_user_id", nullable = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TripClaimStatus status = TripClaimStatus.PENDING;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TripClaimStatus.PENDING;
        }
    }
}
