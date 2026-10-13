package fu.se184491.loadmaster_be.entity.cargo;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "package_code", length = 100)
    private String packageCode;

    @Column(name = "tracking_barcode", length = 100, unique = true)
    private String trackingBarcode;

    @Column(name = "qr_token", length = 64, unique = true, nullable = false, updatable = false)
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_class", length = 20, nullable = false)
    @Builder.Default
    private HandlingClass handlingClass = HandlingClass.STANDARD;

    @Positive
    @Column(name = "actual_length")
    private Integer actualLength;

    /** Package width in millimetres (imported or measured). */
    @Positive
    @Column(name = "actual_width")
    private Integer actualWidth;

    /** Package height in millimetres (imported or measured). */
    @Positive
    @Column(name = "actual_height")
    private Integer actualHeight;

    @Positive
    @Column(name = "actual_weight_kg", precision = 10, scale = 2)
    private BigDecimal actualWeightKg;


    /** Free-text destination (address or code) as written in the import file. */
    @Column(name = "destination", length = 255)
    private String destination;

    @Builder.Default
    @Column(name = "is_pinned", nullable = false)
    private Boolean isPinned = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private PackageStatus status = PackageStatus.PENDING;


    @PrePersist
    public void prePersist() {
        if (this.qrToken == null || this.qrToken.trim().isEmpty()) {
            this.qrToken = UUID.randomUUID().toString();
        }
        if (this.handlingClass == null) {
            this.handlingClass = HandlingClass.STANDARD;
        }
        if (this.isPinned == null) {
            this.isPinned = false;
        }
        if (this.status == null) {
            this.status = PackageStatus.PENDING;
        }
    }
}
