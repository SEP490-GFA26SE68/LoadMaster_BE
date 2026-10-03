package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.route.StopSequenceRequest;
import fu.se184491.loadmaster_be.dto.route.StopSequenceResult;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StopSequenceOptimizer {

    private static final long DEADLINE_RISK_SECONDS = 30 * 60;

    private final Clock clock;

    public StopSequenceOptimizer() {
        this(Clock.systemDefaultZone());
    }

    public StopSequenceOptimizer(Clock clock) {
        this.clock = clock;
    }

    public StopSequenceResult optimize(StopSequenceRequest request) {
        List<StopSequenceRequest.Stop> stops = request.getStops();
        int[][] travelSeconds = request.getDistanceMatrix();
        List<Long> orderedStops = new ArrayList<>();
        List<Long> deadlineMissedStops = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int currentIndex = 0;
        int totalDuration = 0;

        while (visited.size() < stops.size()) {
            int nextStopIndex = selectNextStop(
                    currentIndex,
                    totalDuration,
                    stops,
                    visited,
                    travelSeconds);
            totalDuration += travelSeconds[currentIndex][nextStopIndex + 1];
            StopSequenceRequest.Stop nextStop = stops.get(nextStopIndex);
            orderedStops.add(nextStop.id());
            if (nextStop.deadline() != null
                    && LocalDateTime.now(clock).plusSeconds(totalDuration).isAfter(nextStop.deadline())) {
                deadlineMissedStops.add(nextStop.id());
            }
            visited.add(nextStopIndex);
            currentIndex = nextStopIndex + 1;
        }

        return new StopSequenceResult(
                List.copyOf(orderedStops),
                totalDuration,
                List.copyOf(deadlineMissedStops));
    }

    private int selectNextStop(
            int currentIndex,
            int elapsedSeconds,
            List<StopSequenceRequest.Stop> stops,
            Set<Integer> visited,
            int[][] travelSeconds
    ) {
        LocalDateTime departure = LocalDateTime.now(clock).plusSeconds(elapsedSeconds);
        int earliestRiskyStop = -1;
        LocalDateTime earliestDeadline = null;
        for (int stopIndex = 0; stopIndex < stops.size(); stopIndex++) {
            if (visited.contains(stopIndex)) {
                continue;
            }
            StopSequenceRequest.Stop stop = stops.get(stopIndex);
            if (stop.deadline() == null) {
                continue;
            }
            LocalDateTime directArrival = departure.plusSeconds(
                    travelSeconds[currentIndex][stopIndex + 1]);
            long slackSeconds = Duration.between(directArrival, stop.deadline()).getSeconds();
            if (slackSeconds <= DEADLINE_RISK_SECONDS
                    && (earliestDeadline == null || stop.deadline().isBefore(earliestDeadline))) {
                earliestRiskyStop = stopIndex;
                earliestDeadline = stop.deadline();
            }
        }
        if (earliestRiskyStop >= 0) {
            return earliestRiskyStop;
        }
        return nearestStop(currentIndex, stops.size(), visited, travelSeconds);
    }

    private int nearestStop(
            int currentIndex,
            int stopCount,
            Set<Integer> visited,
            int[][] travelSeconds
    ) {
        int nearest = -1;
        int shortestDuration = Integer.MAX_VALUE;
        for (int stopIndex = 0; stopIndex < stopCount; stopIndex++) {
            if (visited.contains(stopIndex)) {
                continue;
            }
            int duration = travelSeconds[currentIndex][stopIndex + 1];
            if (duration < shortestDuration) {
                shortestDuration = duration;
                nearest = stopIndex;
            }
        }
        return nearest;
    }
}
