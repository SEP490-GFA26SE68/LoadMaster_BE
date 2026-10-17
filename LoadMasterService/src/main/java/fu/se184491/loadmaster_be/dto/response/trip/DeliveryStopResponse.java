
package fu.se184491.loadmaster_be.dto.response.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
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
public class DeliveryStopResponse {

    private Long id;

    private Long tripId;

    // Yêu cầu giao đã tạo ra điểm này.
    // null nếu là điểm giao thêm thủ công.
    private Long deliveryRequirementId;

    // Thứ tự điểm giao trong chuyến.
    private Integer stopSequence;

    private String stopName;

    private String address;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private DeliveryStopStatus status;

    private LocalDateTime plannedArrival;

    private LocalDateTime actualArrival;
}
