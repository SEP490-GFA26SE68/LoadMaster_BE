package fu.se184491.loadmaster_be.dto.route;

import java.util.List;

public class RoutePlanCandidateResponse {

    private final Long id;
    private final String label;
    private final String objective;
    private final boolean sameStopOrderAsA;
    private final RouteOptimizationResult result;

    public RoutePlanCandidateResponse(
            Long id,
            String label,
            String objective,
            boolean sameStopOrderAsA,
            RouteOptimizationResult result
    ) {
        this.id = id;
        this.label = label;
        this.objective = objective;
        this.sameStopOrderAsA = sameStopOrderAsA;
        this.result = result;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getObjective() {
        return objective;
    }

    public boolean isSameStopOrderAsA() {
        return sameStopOrderAsA;
    }

    public RouteOptimizationResult getResult() {
        return result;
    }

    public List<Long> getOrderedStops() {
        return result.getOrderedStops();
    }

    public int getTotalTravelDurationSeconds() {
        return result.getTotalTravelDurationSeconds();
    }

    public long getTotalDistanceMeters() {
        return result.getTotalDistanceMeters();
    }

    public int getEstimatedTotalDurationSeconds() {
        return result.getEstimatedTotalDurationSeconds();
    }

    public List<Long> getDeadlineMissedStops() {
        return result.getDeadlineMissedStops();
    }
}
