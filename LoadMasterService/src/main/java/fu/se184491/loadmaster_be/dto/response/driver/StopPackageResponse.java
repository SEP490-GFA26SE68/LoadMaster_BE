package fu.se184491.loadmaster_be.dto.response.driver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Thông tin kiện hàng cần dỡ tại điểm dừng")
public class StopPackageResponse {

    @Schema(description = "ID kiện hàng")
    private Long packageId;

    @Schema(description = "Tên loại kiện hàng hoặc mô tả")
    private String packageName;

    @Schema(description = "Mã barcode theo dõi")
    private String trackingBarcode;

    @Schema(description = "Khối lượng thực tế (kg)")
    private BigDecimal weight;

    @Schema(description = "Thứ tự đã xếp lên xe (bốc xếp)")
    private Integer stepSequence;

    @Schema(description = "Thứ tự dỡ hàng đề xuất (LIFO - ngược với thứ tự xếp)")
    private Integer unloadOrder;
}
