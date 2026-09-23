package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadPlanResponse {

    private Long id;
    private String planName;
    private Integer packedItemsCount;
    private BigDecimal volumeUtilization;
    private BigDecimal weightUtilization;
    private Boolean approved;
    private Long approvedById;
    private List<PackagePlacementResponse> placements;
}
