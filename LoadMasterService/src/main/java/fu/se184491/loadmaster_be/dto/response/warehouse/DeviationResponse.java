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
public class DeviationResponse {

    private Long deviationId;
    private Long placementId;
    private Integer actualPosX;
    private Integer actualPosY;
    private Integer actualPosZ;
    private String reason;
    private Integer totalDeviations;
    private LocalDateTime reportedAt;
    private PlacementStepResponse nextPlacement;
}
