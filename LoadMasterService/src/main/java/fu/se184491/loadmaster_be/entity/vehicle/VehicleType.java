package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.entity.company.Company;


import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vehicle_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VehicleType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Positive
    @Column(name = "inner_length", nullable = false)
    private Integer innerLength;

    @Positive
    @Column(name = "inner_width", nullable = false)
    private Integer innerWidth;

    @Positive
    @Column(name = "inner_height", nullable = false)
    private Integer innerHeight;

    @Column(
            name = "max_payload_kg",
            precision = 10,
            scale = 2,
            nullable = false
    )
    private BigDecimal maxPayloadKg;

    @Column(name = "front_axle_limit_kg", precision = 10, scale = 2)
    private BigDecimal frontAxleLimitKg;

    @Column(name = "rear_axle_limit_kg", precision = 10, scale = 2)
    private BigDecimal rearAxleLimitKg;

    @Builder.Default
    @Column(
            name = "max_cog_offset_ratio",
            precision = 5,
            scale = 4,
            nullable = false
    )
    private BigDecimal maxCogOffsetRatio =
            new BigDecimal("0.15");

    @Builder.Default
    @Column(name = "hazardous_capable", nullable = false)
    private Boolean hazardousCapable = false;

    @OneToMany(
            mappedBy = "vehicleType",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<VehicleTypeObstacle> obstacles =
            new ArrayList<>();
    // Kích thước cửa khoang hàng, đơn vị cm
    @Positive
    @Column(name = "door_width")
    private Integer doorWidth;

    @Positive
    @Column(name = "door_height")
    private Integer doorHeight;
    public void addObstacle(
            VehicleTypeObstacle obstacle
    ) {
        obstacles.add(obstacle);
        obstacle.setVehicleType(this);
    }

    public void removeObstacle(
            VehicleTypeObstacle obstacle
    ) {
        obstacles.remove(obstacle);
        obstacle.setVehicleType(null);
    }

}
