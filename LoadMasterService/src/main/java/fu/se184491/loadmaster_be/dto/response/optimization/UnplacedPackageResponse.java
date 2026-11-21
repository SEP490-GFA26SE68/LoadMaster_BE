package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Kiện không xếp được của phương án. {@code reason} là mã do dịch vụ tối ưu ghi. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnplacedPackageResponse {
    private Long packageId;
    private String packageCode;
    private String trackingBarcode;
    private String reason;
    /**
     * Mã ràng buộc cứng khi {@code reason} là {@code CONSTRAINT_VIOLATED}
     * ({@code AXLE_CONFIG_MISSING}, {@code PACKAGE_CANNOT_PASS_DOOR}, …). Rỗng khi solver không ghi.
     */
    private List<String> violatedConstraints;
    /** Kích thước (cm) và khối lượng (kg) của kiện; null khi kiện không còn. */
    private BigDecimal lengthCm;
    private BigDecimal widthCm;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
}
