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
@Schema(description = "Thông tin chuyến đi của tài xế")
public class DriverTripResponse {

    @Schema(description = "ID chuyến đi")
    private Long tripId;

    @Schema(description = "Mã chuyến đi")
    private String tripCode;

    @Schema(description = "Tên chuyến do điều phối viên đặt; chuyến chưa đặt tên thì null")
    private String tripName;

    @Schema(description = "Biển số xe")
    private String vehicleLicensePlate;

    @Schema(description = "Tổng số điểm dừng giao hàng")
    private Integer stopCount;

    @Schema(description = "Tổng số kiện hàng của chuyến")
    private Integer totalPackages;

    @Schema(description = "Tổng khối lượng kiện hàng (kg)")
    private BigDecimal totalWeightKg;

    @Schema(description = "Tổng quãng đường theo tuyến hiện hành (mét)")
    private Integer totalDistanceMeters;

    @Schema(description = "Tổng thời gian di chuyển dự kiến (giây)")
    private Integer totalDurationSeconds;

    @Schema(description = "Thời gian ước tính dự kiến hoàn thành chuyến")
    private LocalDateTime estimatedCompletionTime;

    @Schema(description = "Thời gian xuất phát dự kiến")
    private LocalDateTime departureTime;

    @Schema(description = "Trạng thái chuyến đi")
    private String status;

    @Schema(description = "Kho đã xếp xong hàng (có phiên xếp COMPLETED): chuyến LOADING thì tài xế xuất phát được")
    private Boolean loadingCompleted;

    @Schema(description = "Giờ giao xong chuyến (DELIVERED): giờ hoàn tất điểm cuối; chuyến cũ chưa ghi thì giờ đến điểm cuối. Chuyến chưa giao xong: null")
    private LocalDateTime completedAt;
}
