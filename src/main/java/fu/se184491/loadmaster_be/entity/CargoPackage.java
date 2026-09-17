package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.PackageStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "packages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CargoPackage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id")
    private TransportOrder order;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "package_type_id")
    private PackageType packageType;

    @Column(name = "tracking_barcode", length = 100, unique = true)
    private String trackingBarcode;

    @Positive
    @Column(name = "actual_weight_kg", precision = 10, scale = 2)
    private BigDecimal actualWeightKg;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private PackageStatus status;
}
