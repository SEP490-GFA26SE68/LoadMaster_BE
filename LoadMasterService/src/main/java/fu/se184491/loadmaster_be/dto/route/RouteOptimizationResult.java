package fu.se184491.loadmaster_be.dto.route;

import java.time.LocalDateTime;
import java.util.List;

public class RouteOptimizationResult {

    private final Origin origin;
    private final List<Long> orderedStops;
    private final int totalTravelDurationSeconds;
    private final long totalDistanceMeters;
    private final int estimatedTotalDurationSeconds;
    private final List<Long> deadlineMissedStops;
    private final List<RouteSegment> segments;
    private final List<StopSchedule> stopSchedules;

    public RouteOptimizationResult(
            Origin origin,
            List<Long> orderedStops,
            int totalTravelDurationSeconds,
            long totalDistanceMeters,
            int estimatedTotalDurationSeconds,
            List<Long> deadlineMissedStops,
            List<RouteSegment> segments,
            List<StopSchedule> stopSchedules
    ) {
        this.origin = origin;
        this.orderedStops = List.copyOf(orderedStops);
        this.totalTravelDurationSeconds = totalTravelDurationSeconds;
        this.totalDistanceMeters = totalDistanceMeters;
        this.estimatedTotalDurationSeconds = estimatedTotalDurationSeconds;
        this.deadlineMissedStops = List.copyOf(deadlineMissedStops);
        this.segments = List.copyOf(segments);
        this.stopSchedules = List.copyOf(stopSchedules);
    }

    public Origin getOrigin() {
        return origin;
    }

    public List<Long> getOrderedStops() {
        return orderedStops;
    }

    public int getTotalTravelDurationSeconds() {
        return totalTravelDurationSeconds;
    }

    public long getTotalDistanceMeters() {
        return totalDistanceMeters;
    }

    public int getEstimatedTotalDurationSeconds() {
        return estimatedTotalDurationSeconds;
    }

    public List<Long> getDeadlineMissedStops() {
        return deadlineMissedStops;
    }

    public List<RouteSegment> getSegments() {
        return segments;
    }

    public List<StopSchedule> getStopSchedules() {
        return stopSchedules;
    }

    public record Origin(double latitude, double longitude) {
    }

    public record RouteSegment(
            Long fromStopId,
            Long toStopId,
            long durationSeconds,
            long distanceMeters,
            String polyline
    ) {
    }

    public record StopSchedule(Long stopId, int stopSequence, LocalDateTime plannedArrival) {
    }
}
