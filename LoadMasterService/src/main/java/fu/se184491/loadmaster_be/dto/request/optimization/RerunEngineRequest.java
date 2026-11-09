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
    /** Hạng gói của công ty lúc chạy lại (BASIC / PRO / ULTIMATE): dịch vụ tối ưu chọn thuật toán theo hạng này. */
    private String subscriptionTier;
    /** Mã thuật toán của hạng (EP_DBLF / EP_DBLF_GA / EP_DBLF_GA_AI), cũng là thuật toán của job chạy lại. */
    private String algorithmName;
}
