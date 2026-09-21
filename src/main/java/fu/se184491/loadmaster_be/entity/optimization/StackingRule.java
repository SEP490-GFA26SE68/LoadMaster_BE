package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;


import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "stacking_rules", uniqueConstraints = @UniqueConstraint(
        name = "uk_stacking_rule_types", columnNames = {"company_id", "bottom_type_id", "top_type_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StackingRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "bottom_type_id")
    private PackageType bottomType;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "top_type_id")
    private PackageType topType;

    @Builder.Default @Column(name = "allowed")
    private Boolean allowed = true;

    @Builder.Default
    @DecimalMin("0.0") @DecimalMax("1.0")
    @Column(name = "min_contact_ratio", precision = 3, scale = 2)
    private BigDecimal minContactRatio = new BigDecimal("0.80");
}
