package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "trip_cargo_packages",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_trip_cargo_package",
            columnNames = {"cargo_package_id"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripCargoPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "cargo_package_id",
        nullable = false
    )
    private CargoPackage cargoPackage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_stop_id")
    private DeliveryStop deliveryStop;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}