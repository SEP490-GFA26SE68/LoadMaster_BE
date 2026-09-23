package fu.se184491.loadmaster_be.dto.request.optimization;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationJobRequest {

    @NotNull(message = "tripId không được để trống")
    private Long tripId;

    @NotNull(message = "objective không được để trống")
    private OptimizationObjective objective;

    @Min(value = 10, message = "timeLimitSec phải ≥ 10")
    @Max(value = 600, message = "timeLimitSec phải ≤ 600")
    @Builder.Default
    private Integer timeLimitSec = 60;

    /** Optional random seed for reproducibility */
    private Long seed;
}
