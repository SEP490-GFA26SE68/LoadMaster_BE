package fu.se184491.loadmaster_be.dto.response.optimization;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationJobResponse {

    private Long id;
    private String jobUuid;
    private Long tripId;
    private OptimizationObjective objective;
    private Integer timeLimitSec;
    private OptimizationJobStatus status;
    private Long computationMs;
}
