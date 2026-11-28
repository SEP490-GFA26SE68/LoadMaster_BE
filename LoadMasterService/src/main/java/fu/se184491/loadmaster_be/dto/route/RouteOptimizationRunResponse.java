package fu.se184491.loadmaster_be.dto.route;

import java.util.List;

/** Kết quả một lần tối ưu tuyến: ba phương án A · B · C, chưa áp lên chuyến cho đến khi duyệt. */
public class RouteOptimizationRunResponse {

    private final String runId;
    private final RouteOptimizationResult.Origin origin;
    private final List<RoutePlanCandidateResponse> plans;

    public RouteOptimizationRunResponse(
            String runId,
            RouteOptimizationResult.Origin origin,
            List<RoutePlanCandidateResponse> plans
    ) {
        this.runId = runId;
        this.origin = origin;
        this.plans = List.copyOf(plans);
    }

    public String getRunId() {
        return runId;
    }

    public RouteOptimizationResult.Origin getOrigin() {
        return origin;
    }

    public List<RoutePlanCandidateResponse> getPlans() {
        return plans;
    }
}
