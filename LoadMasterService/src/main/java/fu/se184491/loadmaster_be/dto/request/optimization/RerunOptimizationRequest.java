package fu.se184491.loadmaster_be.dto.request.optimization;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RerunOptimizationRequest {

    private OptimizationObjective objective;

    @Min(value = 1, message = "VALIDATION_ERROR")
    @Builder.Default
    private Integer timeLimitSec = 60;

    private Integer seed;
}
