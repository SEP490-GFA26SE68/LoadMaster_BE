package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
