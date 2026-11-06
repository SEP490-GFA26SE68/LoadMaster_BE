package fu.se184491.loadmaster_be.dto.response.driver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Thông tin điểm dừng hiện tại của chuyến giao hàng")
public class CurrentStopResponse {

    @Schema(description = "ID điểm dừng")
    private Long stopId;

    @Schema(description = "ID chuyến đi")
    private Long tripId;

    @Schema(description = "Thứ tự điểm dừng trên lộ trình")
    private Integer stopSequence;

    @Schema(description = "Tên điểm dừng giao hàng")
    private String stopName;

    @Schema(description = "Địa chỉ giao hàng")
    private String address;

    @Schema(description = "Trạng thái điểm dừng")
    private String status;

    @Schema(description = "Giờ đến thật của điểm (giờ Việt Nam, không múi giờ); null khi chưa báo đến")
    private LocalDateTime actualArrival;

    @Schema(description = "Danh sách kiện hàng cần dỡ theo thứ tự khuyến nghị LIFO")
    private List<StopPackageResponse> packages;

    @Schema(description = "Loại điểm: DELIVERY (giao) hoặc PICKUP (nhận hàng dọc đường)")
    private String stopType;

    @Schema(description = "Kiện phải nhận ở điểm nhận (rỗng với điểm giao)")
    private List<StopPackageResponse> pickupPackages;
}
