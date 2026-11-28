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

/**
 * Thứ tự điểm: gần nhất, trừ khi một điểm sắp trễ hạn (slack ≤ 30 phút). Nhiều điểm gấp thì hạn sớm hơn,
 * rồi ưu tiên cao hơn (D-93), rồi gần hơn. {@link SequencePolicy#DEADLINE_FIRST} (phương án C) khi chưa gấp
 * hạn thì đi điểm hạn sớm hơn trước, không đi gần nhất.
 */
public class StopSequenceOptimizer {

    public enum SequencePolicy {
        /** A/B: gần nhất, nhảy sang điểm hạn gấp. */
        NEAREST_WITH_DEADLINE_RISK,
        /** C: hạn sớm hơn trước (rồi ưu tiên), vẫn nhảy khi slack ≤ 30 phút. */
        DEADLINE_FIRST
    }

    private static final long DEADLINE_RISK_SECONDS = 30 * 60;
    private static final int SERVICE_TIME_SECONDS = 15 * 60;

    private final Clock clock;

    public StopSequenceOptimizer() {
        this(Clock.systemDefaultZone());
    }

    public StopSequenceOptimizer(Clock clock) {
        this.clock = clock;
    }

    public StopSequenceResult optimize(StopSequenceRequest request) {
        return optimize(request, LocalDateTime.now(clock), SequencePolicy.NEAREST_WITH_DEADLINE_RISK);
    }

    public StopSequenceResult optimize(StopSequenceRequest request, LocalDateTime departureTime) {
        return optimize(request, departureTime, SequencePolicy.NEAREST_WITH_DEADLINE_RISK);
    }

    public StopSequenceResult optimize(
            StopSequenceRequest request,
            LocalDateTime departureTime,
            SequencePolicy policy
    ) {
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
                    departureTime,
                    totalDuration,
                    stops,
                    visited,
                    travelSeconds,
                    policy
            );

            totalDuration += travelSeconds[currentIndex][nextStopIndex + 1];

            StopSequenceRequest.Stop nextStop = stops.get(nextStopIndex);
            LocalDateTime plannedArrival = departureTime.plusSeconds(totalDuration);
            orderedStops.add(nextStop.id());

            if (nextStop.deadline() != null && plannedArrival.isAfter(nextStop.deadline())) {
                deadlineMissedStops.add(nextStop.id());
            }

            visited.add(nextStopIndex);
            currentIndex = nextStopIndex + 1;
            totalDuration += SERVICE_TIME_SECONDS;
        }

        return new StopSequenceResult(
                List.copyOf(orderedStops),
                totalDuration,
                List.copyOf(deadlineMissedStops)
        );
    }

    private int selectNextStop(
            int currentIndex,
            LocalDateTime departureTime,
            int elapsedSeconds,
            List<StopSequenceRequest.Stop> stops,
            Set<Integer> visited,
            int[][] travelSeconds,
            SequencePolicy policy
    ) {
        LocalDateTime departure = departureTime.plusSeconds(elapsedSeconds);
        List<Integer> remaining = new ArrayList<>();
        List<Integer> atRisk = new ArrayList<>();

        for (int stopIndex = 0; stopIndex < stops.size(); stopIndex++) {
            if (visited.contains(stopIndex)) {
                continue;
            }
            remaining.add(stopIndex);
            StopSequenceRequest.Stop stop = stops.get(stopIndex);
            if (stop.deadline() == null) {
                continue;
            }
            LocalDateTime directArrival = departure.plusSeconds(travelSeconds[currentIndex][stopIndex + 1]);
            long slackSeconds = Duration.between(directArrival, stop.deadline()).getSeconds();
            if (slackSeconds <= DEADLINE_RISK_SECONDS) {
                atRisk.add(stopIndex);
            }
        }

        if (!atRisk.isEmpty()) {
            return best(atRisk, currentIndex, stops, travelSeconds, true);
        }
        if (policy == SequencePolicy.DEADLINE_FIRST) {
            return best(remaining, currentIndex, stops, travelSeconds, true);
        }
        return best(remaining, currentIndex, stops, travelSeconds, false);
    }

    /**
     * @param deadlineFirst {@code true}: hạn sớm hơn, rồi ưu tiên, rồi gần. {@code false}: gần rồi hạn rồi ưu tiên.
     */
    private int best(
            List<Integer> candidates,
            int currentIndex,
            List<StopSequenceRequest.Stop> stops,
            int[][] travelSeconds,
            boolean deadlineFirst
    ) {
        int chosen = candidates.getFirst();
        for (int i = 1; i < candidates.size(); i++) {
            int candidate = candidates.get(i);
            if (compare(candidate, chosen, currentIndex, stops, travelSeconds, deadlineFirst) < 0) {
                chosen = candidate;
            }
        }
        return chosen;
    }

    /** Âm khi {@code a} đi trước {@code b}. */
    private int compare(
            int a,
            int b,
            int currentIndex,
            List<StopSequenceRequest.Stop> stops,
            int[][] travelSeconds,
            boolean deadlineFirst
    ) {
        int travelA = travelSeconds[currentIndex][a + 1];
        int travelB = travelSeconds[currentIndex][b + 1];
        int byDeadline = compareDeadlineThenPriority(stops.get(a), stops.get(b));
        if (deadlineFirst) {
            if (byDeadline != 0) {
                return byDeadline;
            }
            if (travelA != travelB) {
                return Integer.compare(travelA, travelB);
            }
            return Integer.compare(a, b);
        }
        if (travelA != travelB) {
            return Integer.compare(travelA, travelB);
        }
        if (byDeadline != 0) {
            return byDeadline;
        }
        return Integer.compare(a, b);
    }

    /** Hạn sớm hơn trước; không hạn xếp sau; cùng hạn thì ưu tiên cao hơn. */
    static int compareDeadlineThenPriority(StopSequenceRequest.Stop a, StopSequenceRequest.Stop b) {
        if (a.deadline() == null && b.deadline() == null) {
            return Integer.compare(b.priority(), a.priority());
        }
        if (a.deadline() == null) {
            return 1;
        }
        if (b.deadline() == null) {
            return -1;
        }
        int byDeadline = a.deadline().compareTo(b.deadline());
        if (byDeadline != 0) {
            return byDeadline;
        }
        return Integer.compare(b.priority(), a.priority());
    }
}
