package fu.se184491.loadmaster_be.service.route;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteAuditSummaryTest {

    @Test
    void auditOfRouteOptimizationKeepsOnlyTheSummary() {
        Map<String, Object> summary = RouteOptimizationService.routeAuditSummary(
                4, 23_456L, 5_430, List.of(7L), 31L, 2);

        assertThat(summary).containsOnlyKeys(
                "stopCount", "distanceKm", "durationMinutes", "deadlineMissedCount", "routePlanId", "planVersion");
        assertThat(summary)
                .containsEntry("stopCount", 4)
                .containsEntry("distanceKm", 23.5)
                .containsEntry("durationMinutes", 91L)
                .containsEntry("deadlineMissedCount", 1)
                .containsEntry("routePlanId", 31L)
                .containsEntry("planVersion", 2);
    }

    @Test
    void missingDeadlineListCountsAsZero() {
        Map<String, Object> summary = RouteOptimizationService.routeAuditSummary(0, 0L, 0, null, null, null);

        assertThat(summary)
                .containsEntry("distanceKm", 0.0)
                .containsEntry("durationMinutes", 0L)
                .containsEntry("deadlineMissedCount", 0);
    }
}
