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
@Schema(description = "Tài xế báo đã đến điểm giao")
public class ArriveStopResponse {

    @Schema(description = "ID điểm giao")
    private Long stopId;

    @Schema(description = "ID chuyến")
    private Long tripId;

    @Schema(description = "Thứ tự điểm giao trong chuyến")
    private Integer stopSequence;

    @Schema(description = "Tên điểm giao")
    private String stopName;

    @Schema(description = "Trạng thái điểm giao sau khi báo đến (ARRIVED)")
    private String status;

    @Schema(description = "Giờ đến thật (giờ Việt Nam, không múi giờ)")
    private LocalDateTime actualArrival;
}
