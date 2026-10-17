package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteGuidanceStepResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteGuidanceMapperTest {

    @Test
    @DisplayName("Goong step html is stripped and stored as a guidance map")
    void goongStepBecomesGuidanceMap() {
        DirectionsResponse.Step step = new DirectionsResponse.Step();
        step.setHtmlInstructions("Rẽ <b>phải</b> vào <b>Nguyễn Huệ</b>");
        step.setManeuver("turn-right");
        step.setDistance(new DistanceMatrixResponse.Measure("0.3 km", 300));
        step.setDuration(new DistanceMatrixResponse.Measure("1 phút", 45));
        DirectionsResponse.Location start = new DirectionsResponse.Location();
        start.setLat(10.776);
        start.setLng(106.7009);
        step.setStartLocation(start);

        DirectionsResponse.Leg leg = new DirectionsResponse.Leg();
        leg.setSteps(List.of(step));
        DirectionsResponse.Route route = new DirectionsResponse.Route();
        route.setLegs(List.of(leg));

        List<Map<String, Object>> maps = route.toGuidanceSteps();
        assertEquals(1, maps.size());
        assertEquals("Rẽ phải vào Nguyễn Huệ", maps.getFirst().get("instruction"));
        assertEquals("turn-right", maps.getFirst().get("maneuver"));
        assertEquals(300, maps.getFirst().get("distanceMeters"));

        List<RouteGuidanceStepResponse> responses = RouteGuidanceMapper.toResponses(maps);
        assertEquals(1, responses.size());
        assertEquals("Rẽ phải vào Nguyễn Huệ", responses.getFirst().getInstruction());
        assertEquals(10.776, responses.getFirst().getLat());
    }

    @Test
    @DisplayName("empty steps stay empty")
    void emptySteps() {
        assertTrue(RouteGuidanceMapper.toResponses(null).isEmpty());
        assertTrue(RouteGuidanceMapper.toResponses(List.of()).isEmpty());
    }
}
