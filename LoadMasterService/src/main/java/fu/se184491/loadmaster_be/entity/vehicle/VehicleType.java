package fu.se184491.loadmaster_be.entity.vehicle;

import fu.se184491.loadmaster_be.entity.company.Company;


import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicle_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VehicleType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "name", length = 100)
    private String name;

    @Positive @Column(name = "inner_length")
    private Integer innerLength;

    @Positive @Column(name = "inner_width")
    private Integer innerWidth;

    @Positive @Column(name = "inner_height")
    private Integer innerHeight;

    @Column(name = "max_payload_kg", precision = 10, scale = 2)
    private BigDecimal maxPayloadKg;

    @Positive @Column(name = "door_width")
    private Integer doorWidth;

    @Positive @Column(name = "door_height")
    private Integer doorHeight;
}
