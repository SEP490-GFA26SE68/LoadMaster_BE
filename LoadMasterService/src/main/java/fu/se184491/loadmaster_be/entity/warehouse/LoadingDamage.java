package fu.se184491.loadmaster_be.entity.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Kho báo một kiện hỏng lúc xếp. {@code LEFT_OUT}: kiện bị bỏ lại kho, vị trí của nó trong phương án tính là đã xử lý. {@code REPLANNED}:
 * chuyến đã quay về Đã lập kế hoạch.
 */
@Entity
@Table(name = "loading_damages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoadingDamage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "placement_id", nullable = false)
    private PackagePlacement placement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id")
    private CargoPackage cargoPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private DamageOutcome outcome;

    /** Số kiện tựa lên kiện hỏng trong phương án. */
    @Column(name = "resting_count")
    private Integer restingCount;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by")
    private User reportedBy;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;
}
