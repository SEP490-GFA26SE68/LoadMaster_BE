package fu.se184491.loadmaster_be.dto.response.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartLoadingResponse {

    private Long executionId;
    private Long tripId;
    private String tripCode;
    private String vehicleInfo;
    private Integer totalPackages;
    private String status;
    private LocalDateTime startedAt;
    private List<PlacementStepResponse> placements;
}
