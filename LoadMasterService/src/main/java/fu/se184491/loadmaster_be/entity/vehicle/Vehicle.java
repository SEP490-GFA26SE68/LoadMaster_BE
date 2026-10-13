package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "company_id",
            nullable = false
    )
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "vehicle_type_id",
            nullable = false
    )
    private VehicleType vehicleType;

    @Column(
            name = "name",
            length = 150,
            nullable = false
    )
    private String name;

    @Column(
            name = "license_plate",
            length = 50,
            unique = true,
            nullable = false
    )
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    @Column(name = "maintenance_note", columnDefinition = "TEXT")
    private String maintenanceNote;
}