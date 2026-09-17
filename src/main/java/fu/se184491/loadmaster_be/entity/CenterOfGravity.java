package fu.se184491.loadmaster_be.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "centers_of_gravity")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CenterOfGravity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "load_plan_id", unique = true, nullable = false)
    private LoadPlan loadPlan;

    @Column(name = "cog_x", precision = 8, scale = 2) private BigDecimal cogX;
    @Column(name = "cog_y", precision = 8, scale = 2) private BigDecimal cogY;
    @Column(name = "cog_z", precision = 8, scale = 2) private BigDecimal cogZ;
    @Column(name = "front_axle_load_kg", precision = 10, scale = 2) private BigDecimal frontAxleLoadKg;
    @Column(name = "rear_axle_load_kg", precision = 10, scale = 2) private BigDecimal rearAxleLoadKg;
    @Column(name = "is_balanced") private Boolean balanced;
}
