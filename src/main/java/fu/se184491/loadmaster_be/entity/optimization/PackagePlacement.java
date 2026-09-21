package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;


import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

@Entity
@Table(name = "package_placements", uniqueConstraints = @UniqueConstraint(
        name = "uk_placement_plan_package", columnNames = {"load_plan_id", "package_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PackagePlacement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "load_plan_id")
    private LoadPlan loadPlan;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "package_id")
    private CargoPackage cargoPackage;

    @Column(name = "pos_x") private Integer posX;
    @Column(name = "pos_y") private Integer posY;
    @Column(name = "pos_z") private Integer posZ;
    @Column(name = "packed_length") private Integer packedLength;
    @Column(name = "packed_width") private Integer packedWidth;
    @Column(name = "packed_height") private Integer packedHeight;

    @Min(0) @Max(5) @Column(name = "rotation_type")
    private Integer rotationType;

    @Column(name = "step_sequence")
    private Integer stepSequence;

    @Builder.Default @Column(name = "pinned")
    private Boolean pinned = false;
}
