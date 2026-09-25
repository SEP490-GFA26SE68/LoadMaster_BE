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
public class LoadPlanResponse {

    private Long id;
    private Long jobId;
    private String jobUuid;
    private String planName;
    private Integer version;
    private Long parentPlanId;
    private Integer packedItemsCount;
    private BigDecimal volumeUtilization;
    private BigDecimal weightUtilization;
    private Boolean approved;
    private List<PackagePlacementResponse> placements;
}
