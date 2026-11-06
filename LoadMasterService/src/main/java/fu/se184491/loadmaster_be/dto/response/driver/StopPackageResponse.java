package fu.se184491.loadmaster_be.dto.response.driver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Schema(description = "Mã kiện của bên gửi")
    private String packageCode;

    @Schema(description = "Mã QR in trên nhãn kiện")
    private String qrToken;

    @Schema(description = "Kiện đã được dỡ chưa")
    private Boolean unloaded;

    @Schema(description = "Thời điểm dỡ (giờ Việt Nam, không múi giờ); null khi chưa dỡ")
    private LocalDateTime unloadedAt;

    @Schema(description = "Sự cố tài xế báo gần nhất cho kiện ở điểm này: DAMAGED, MISSING, REFUSED, OTHER; null khi không có")
    private String issueKind;

    @Schema(description = "Ghi chú của sự cố gần nhất")
    private String issueNote;

    @Schema(description = "Xác nhận tay gần nhất của kiện ở điểm này: PENDING, APPROVED, REJECTED; null khi không có")
    private String manualStatus;

    @Schema(description = "Lý do tài xế xác nhận tay gần nhất: LABEL_DAMAGED, QR_UNREADABLE, OTHER")
    private String manualReason;

    @Schema(description = "Lý do điều phối viên từ chối xác nhận tay gần nhất")
    private String manualRejectReason;
}
