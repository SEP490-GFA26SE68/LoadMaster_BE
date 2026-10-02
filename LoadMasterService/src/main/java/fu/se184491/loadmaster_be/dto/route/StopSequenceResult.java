package fu.se184491.loadmaster_be.dto.route;

import java.util.List;

public class StopSequenceResult {

    private final List<Long> orderedStops;
    private final int estimatedTotalDuration;
    private final List<Long> deadlineMissedStops;

    public StopSequenceResult(
            List<Long> orderedStops,
            int estimatedTotalDuration,
            List<Long> deadlineMissedStops
    ) {
        this.orderedStops = orderedStops;
        this.estimatedTotalDuration = estimatedTotalDuration;
        this.deadlineMissedStops = deadlineMissedStops;
    }

    public List<Long> getOrderedStops() {
        return orderedStops;
    }

    public int getEstimatedTotalDuration() {
        return estimatedTotalDuration;
    }

    public List<Long> getDeadlineMissedStops() {
        return deadlineMissedStops;
    }
}
