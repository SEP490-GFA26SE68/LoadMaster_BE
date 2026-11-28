package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RoutePathSelectorTest {

    @Test
    void picksTheShortestDurationRoute() {
        DirectionsResponse.Route slow = route(9000, 800, "slow");
        DirectionsResponse.Route fast = route(8000, 500, "fast");
        assertSame(fast, RoutePathSelector.shortest(new DirectionsResponse(List.of(slow, fast))));
    }

    @Test
    void quieterInRushHourPrefersTheLongerAlternativeWithinCap() {
        DirectionsResponse.Route shortest = route(5000, 400, "short");
        DirectionsResponse.Route longer = route(6200, 520, "quiet");
        DirectionsResponse response = new DirectionsResponse(List.of(shortest, longer));
        LocalDateTime rush = LocalDateTime.of(2026, 11, 28, 7, 30);

        assertEquals("quiet", RoutePathSelector.quieter(response, shortest, rush).getPolyline());
    }

    @Test
    void quieterFallsBackToShortestWhenOnlyOneRoute() {
        DirectionsResponse.Route only = route(5000, 400, "only");
        DirectionsResponse response = new DirectionsResponse(List.of(only));
        assertSame(only, RoutePathSelector.quieter(response, only, LocalDateTime.of(2026, 11, 28, 8, 0)));
    }

    private static DirectionsResponse.Route route(long meters, long seconds, String polyline) {
        return new DirectionsResponse.Route(
                List.of(new DirectionsResponse.Leg(
                        new DistanceMatrixResponse.Measure("", meters),
                        new DistanceMatrixResponse.Measure("", seconds))),
                new DirectionsResponse.OverviewPolyline(polyline));
    }
}
