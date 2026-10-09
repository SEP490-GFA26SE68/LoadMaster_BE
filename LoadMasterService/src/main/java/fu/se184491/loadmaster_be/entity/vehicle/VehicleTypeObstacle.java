package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.ObstacleType;
import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    @Column(name = "z", nullable = false)
    private Integer z;

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