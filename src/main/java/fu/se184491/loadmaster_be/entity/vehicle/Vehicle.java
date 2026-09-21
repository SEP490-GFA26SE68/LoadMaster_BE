package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;


import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Vehicle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "vehicle_type_id")
    private VehicleType vehicleType;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "driver_id")
    private User driver;

    @Column(name = "license_plate", length = 30, unique = true, nullable = false)
    private String licensePlate;

    @Column(name = "front_axle_limit_kg", precision = 10, scale = 2)
    private BigDecimal frontAxleLimitKg;

    @Column(name = "rear_axle_limit_kg", precision = 10, scale = 2)
    private BigDecimal rearAxleLimitKg;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private VehicleStatus status;
}
