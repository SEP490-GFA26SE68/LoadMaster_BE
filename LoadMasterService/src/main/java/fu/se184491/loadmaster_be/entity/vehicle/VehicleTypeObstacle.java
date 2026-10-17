package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.ObstacleType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicle_type_obstacles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleTypeObstacle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "vehicle_type_id",
            nullable = false
    )
    private VehicleType vehicleType;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 50, nullable = false)
    private ObstacleType type;

    @Column(name = "x", precision = 10, scale = 1, nullable = false)
    private BigDecimal x;

    @Column(name = "y", precision = 10, scale = 1, nullable = false)
    private BigDecimal y;

    @Column(name = "z", precision = 10, scale = 1, nullable = false)
    private BigDecimal z;

    @Column(name = "length", nullable = false)
    private Integer length;

    @Column(name = "width", nullable = false)
    private Integer width;

    @Column(name = "height", nullable = false)
    private Integer height;

    @Builder.Default
    @Column(name = "load_bearing", nullable = false)
    private Boolean loadBearing = false;
}