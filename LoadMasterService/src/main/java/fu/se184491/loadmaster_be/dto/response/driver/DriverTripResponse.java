package fu.se184491.loadmaster_be.dto.response.driver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Schema(description = "Biển số xe")
    private String vehicleLicensePlate;

    @Schema(description = "Tổng số điểm dừng giao hàng")
    private Integer stopCount;

    @Schema(description = "Thời gian xuất phát dự kiến")
    private LocalDateTime departureTime;

    @Schema(description = "Trạng thái chuyến đi")
    private String status;
}
