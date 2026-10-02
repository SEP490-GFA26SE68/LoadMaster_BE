package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.dto.route.StopSequenceRequest;
import fu.se184491.loadmaster_be.dto.route.StopSequenceResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StopSequenceOptimizerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void visitsTheNearestUnvisitedStopAtEachStep() {
        StopSequenceOptimizer optimizer = new StopSequenceOptimizer(CLOCK);
        LocalDateTime nextWeek = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(7);
        StopSequenceRequest request = new StopSequenceRequest(
                new LatLng(10.70, 106.60),
                List.of(
                        new StopSequenceRequest.Stop(101L, 10.71, 106.61, nextWeek, 1),
                        new StopSequenceRequest.Stop(102L, 10.72, 106.62, nextWeek, 1),
                        new StopSequenceRequest.Stop(103L, 10.73, 106.63, nextWeek, 1)
                ),
                new int[][]{
                        {0, 30, 10, 20},
                        {30, 0, 20, 5},
                        {10, 20, 0, 5},
                        {20, 5, 5, 0}
                });

        StopSequenceResult result = optimizer.optimize(request);

        assertEquals(List.of(102L, 103L, 101L), result.getOrderedStops());
        assertEquals(20, result.getEstimatedTotalDuration());
        assertEquals(List.of(), result.getDeadlineMissedStops());
    }

    @Test
    void prioritizesTomorrowDeadlineWhenItsSlackFallsBelowThirtyMinutes() {
        StopSequenceOptimizer optimizer = new StopSequenceOptimizer(CLOCK);
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        StopSequenceRequest request = new StopSequenceRequest(
                new LatLng(10.70, 106.60),
                List.of(
                        new StopSequenceRequest.Stop(201L, 10.71, 106.61, now.plusDays(1), 3),
                        new StopSequenceRequest.Stop(202L, 10.72, 106.62, now.plusWeeks(1), 2),
                        new StopSequenceRequest.Stop(203L, 10.73, 106.63, now.plusWeeks(1), 1)
                ),
                new int[][]{
                        {0, 85_200, 60, 120},
                        {85_200, 0, 60, 120},
                        {60, 60, 0, 60},
                        {120, 120, 60, 0}
                });

        StopSequenceResult result = optimizer.optimize(request);

        assertEquals(List.of(201L, 202L, 203L), result.getOrderedStops());
        assertEquals(85_320, result.getEstimatedTotalDuration());
        assertEquals(List.of(), result.getDeadlineMissedStops());
    }

    @Test
    void returnsBestEffortRouteAndIdentifiesStopsWhoseDeadlinesCannotBeMet() {
        StopSequenceOptimizer optimizer = new StopSequenceOptimizer(CLOCK);
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        StopSequenceRequest request = new StopSequenceRequest(
                new LatLng(10.70, 106.60),
                List.of(
                        new StopSequenceRequest.Stop(301L, 10.71, 106.61, now.plusMinutes(5), 1),
                        new StopSequenceRequest.Stop(302L, 10.72, 106.62, now.plusHours(2), 1)
                ),
                new int[][]{
                        {0, 600, 120},
                        {600, 0, 180},
                        {120, 180, 0}
                });

        StopSequenceResult result = optimizer.optimize(request);

        assertEquals(List.of(301L, 302L), result.getOrderedStops());
        assertEquals(780, result.getEstimatedTotalDuration());
        assertEquals(List.of(301L), result.getDeadlineMissedStops());
    }
}
