
package fu.se184491.loadmaster_be.entity.cargo;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "cargo_package_packing_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CargoPackagePackingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mỗi kiện vật lý có tối đa một bộ thông số xếp hàng riêng.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "package_id",
            nullable = false,
            unique = true
    )
    private CargoPackage cargoPackage;

    // Các hướng đặt được yêu cầu/cho phép.
    // Chưa cố định cú pháp trước khi đối chiếu rotationType 0-5.
    @Column(name = "orientation", length = 100)
    private String orientation;

    // Yêu cầu kiện phải giữ thẳng đứng.
    @Column(name = "upright_only")
    private Boolean uprightOnly;

    // Mức độ dễ vỡ chi tiết.
    // Chưa mặc định thứ tự/mức vì chưa chốt quy ước Excel.
    @Min(0)
    @Column(name = "fragility_level")
    private Integer fragilityLevel;

    // Kiện có cho phép đặt hàng khác lên trên không?
    @Column(name = "stackable")
    private Boolean stackable;

    // Tổng khối lượng tối đa được phép đặt phía trên kiện (kg).
    @DecimalMin("0.0")
    @Column(
            name = "max_top_load_kg",
            precision = 10,
            scale = 2
    )
    private BigDecimal maxTopLoadKg;

    // Số tầng xếp tối đa theo chính sách của kiện.
    @Min(1)
    @Column(name = "max_layers")
    private Integer maxLayers;

    // Tỷ lệ diện tích đáy tối thiểu cần được hỗ trợ.
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @Column(
            name = "min_support_ratio",
            precision = 5,
            scale = 4
    )
    private BigDecimal minSupportRatio;

    // Độ ưu tiên khi lựa chọn kiện để xếp.
    @Column(name = "priority")
    private Integer priority;

    // Kiện bắt buộc phải có trong phương án xếp.
    @Column(name = "must_load")
    private Boolean mustLoad;

    // Nhóm kiện theo yêu cầu nhập liệu.
    @Column(name = "packing_group", length = 100)
    private String packingGroup;
}
