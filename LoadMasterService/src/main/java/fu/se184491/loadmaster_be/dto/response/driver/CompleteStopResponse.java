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
@Schema(description = "Kết quả hoàn thành điểm giao hàng")
public class CompleteStopResponse {

    @Schema(description = "ID điểm dừng vừa hoàn thành")
    private Long stopId;

    @Schema(description = "Trạng thái mới của điểm dừng")
    private String stopStatus;

    @Schema(description = "ID điểm dừng tiếp theo cần giao (nếu có)")
    private Long nextStopId;

    @Schema(description = "Trạng thái hiện tại của chuyến đi")
    private String tripStatus;

    @Schema(description = "Đánh dấu chuyến đi đã hoàn tất giao hàng hay chưa")
    private Boolean tripCompleted;

    @Schema(description = "Thông báo kết quả")
    private String message;
}
