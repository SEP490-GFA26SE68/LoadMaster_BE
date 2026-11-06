package fu.se184491.loadmaster_be.entity.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.UniqueConstraint;
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
 * Một kiện kho đã đưa vào khu chờ (soạn hàng) cho một chuyến. Theo chuyến và kiện, không theo phương án: chuyến quay về Đã lập kế hoạch rồi
 * xếp lại thì kiện đã soạn vẫn tính là đã soạn.
 */
@Entity
@Table(name = "staging_confirmations", uniqueConstraints = @UniqueConstraint(
        name = "uk_staging_trip_package", columnNames = {"trip_id", "package_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagingConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private CargoPackage cargoPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 10, nullable = false)
    private StagingMethod method;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staged_by")
    private User stagedBy;

    @Column(name = "staged_at", nullable = false)
    private LocalDateTime stagedAt;
}
