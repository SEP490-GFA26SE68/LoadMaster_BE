package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationResultDto {

    private Integer packedItemsCount;
    private BigDecimal volumeUtilization;
    private BigDecimal weightUtilization;
    private Long computationMs;
    private List<PlacementResultDto> placements;
}
