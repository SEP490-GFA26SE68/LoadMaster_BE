package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;


import fu.se184491.loadmaster_be.constant.optimization.UnplacedReason;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "unplaced_packages", uniqueConstraints = @UniqueConstraint(
        name = "uk_unplaced_plan_package", columnNames = {"load_plan_id", "package_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnplacedPackage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "load_plan_id")
    private LoadPlan loadPlan;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "package_id")
    private CargoPackage cargoPackage;

    @Enumerated(EnumType.STRING) @Column(name = "rejection_reason")
    private UnplacedReason rejectionReason;
}
