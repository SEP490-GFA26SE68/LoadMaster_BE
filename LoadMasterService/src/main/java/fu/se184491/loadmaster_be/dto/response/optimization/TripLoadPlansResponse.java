package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Mọi phương án của một chuyến, cũ trước mới sau; {@code approvedPlanId} là bản duyệt mới nhất (hoặc null). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripLoadPlansResponse {
    private Long tripId;
    private Long approvedPlanId;
    private List<LoadPlanDetailResponse> plans;
}
