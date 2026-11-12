package fu.se184491.loadmaster_be.entity.pickup;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một kiện của yêu cầu nhận: mã của bên gửi, kích thước (cm), khối lượng (kg), loại hàng. Khi yêu cầu được duyệt, kho kiện có một kiện
 * thật ({@code cargo_package_id}, kèm mã QR); tài xế đối chiếu kiện đó ở điểm nhận ({@code picked_at}).
 */
@Entity
@Table(name = "pickup_request_packages",
        uniqueConstraints = @UniqueConstraint(name = "uk_pickup_request_package_line", columnNames = {"pickup_request_id", "line_index"}),
        indexes = @Index(name = "idx_pickup_request_packages_package", columnList = "cargo_package_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PickupRequestPackage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pickup_request_id", nullable = false)
    private PickupRequest pickupRequest;

    /** Thứ tự kiện trong yêu cầu, từ 0: chỗ xếp của kiện trong {@code layout} gọi kiện bằng số này. */
    @Column(name = "line_index", nullable = false)
    private Integer lineIndex;

    @Column(name = "package_code", length = 100, nullable = false)
    private String packageCode;

    @Column(name = "length_cm", precision = 10, scale = 2, nullable = false)
    private BigDecimal lengthCm;

    @Column(name = "width_cm", precision = 10, scale = 2, nullable = false)
    private BigDecimal widthCm;

    @Column(name = "height_cm", precision = 10, scale = 2, nullable = false)
    private BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 10, scale = 2, nullable = false)
    private BigDecimal weightKg;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "handling_class", length = 20, nullable = false)
    private HandlingClass handlingClass = HandlingClass.STANDARD;

    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "cargo_package_id", unique = true)
    private CargoPackage cargoPackage;

    @Column(name = "picked_at")
    private LocalDateTime pickedAt;

    /** Cách tài xế đối chiếu kiện lúc nhận lên xe; dòng cũ để trống là QR. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pick_method", length = 10)
    private LabelMethod pickMethod;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "picked_by")
    private User pickedBy;
}
