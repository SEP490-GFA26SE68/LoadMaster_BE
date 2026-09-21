package fu.se184491.loadmaster_be.entity.cargo;

import fu.se184491.loadmaster_be.entity.company.Company;


import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "package_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PackageType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "type_code", length = 50)
    private String typeCode;

    @Column(name = "name", length = 150)
    private String name;

    @Positive @Column(name = "length")
    private Integer length;

    @Positive @Column(name = "width")
    private Integer width;

    @Positive @Column(name = "height")
    private Integer height;

    @Column(name = "max_stack_weight_kg", precision = 10, scale = 2)
    private BigDecimal maxStackWeightKg;

    @Builder.Default @Column(name = "allow_rotate_x")
    private Boolean allowRotateX = true;

    @Builder.Default @Column(name = "allow_rotate_y")
    private Boolean allowRotateY = true;

    @Builder.Default @Column(name = "allow_rotate_z")
    private Boolean allowRotateZ = true;

    @Builder.Default @Column(name = "is_fragile")
    private Boolean fragile = false;
}
