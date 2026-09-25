package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanMetricsDto {

    private Long planId;
    private String planName;
    private Integer version;
    private BigDecimal volumeUtil;
    private BigDecimal weightUtil;
    private Integer packedCount;
    private Long unplacedCount;
    private Long computeMs;
}
