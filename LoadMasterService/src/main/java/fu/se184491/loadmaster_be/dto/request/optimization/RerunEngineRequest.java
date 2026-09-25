package fu.se184491.loadmaster_be.dto.request.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RerunEngineRequest {

    private String jobUuid;
    private Long tripId;
    private Long parentPlanId;
    private String objective;
    private Integer timeLimitSec;
    private Integer seed;
    private List<PinnedPlacementDto> pinnedPlacements;
}
