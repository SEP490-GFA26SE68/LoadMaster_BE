package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTaskResponse {

    private Long tripId;
    private String tripCode;
    private String vehicleInfo;
    private Integer totalPackages;
    private Long loadPlanId;
    private LocalDateTime departureTime;
    private String status;
}
