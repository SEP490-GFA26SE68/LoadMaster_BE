package fu.se184491.loadmaster_be.dto.response.driver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Kết quả xác nhận dỡ kiện hàng")
public class ConfirmUnloadResponse {

    @Schema(description = "ID kiện hàng đã dỡ")
    private Long packageId;

    @Schema(description = "Mã barcode theo dõi")
    private String trackingBarcode;

    @Schema(description = "ID điểm dừng giao hàng")
    private Long stopId;

    @Schema(description = "Số lượng kiện hàng còn lại cần dỡ tại điểm dừng này")
    private Integer remainingPackagesCount;

    @Schema(description = "Thông báo kết quả")
    private String message;
}
