package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripEntityContractTest {

    @Test
    void newTripStartsAsDraft() {
        assertEquals(TripStatus.DRAFT, Trip.builder().build().getStatus());
    }

    @Test
    void tripExposesRoutePlanningAndSegregationMetadata() {
        Map<String, Object> routePlan = Map.of(
                "distanceMeters", 12500,
                "encodedPolyline", "sample-polyline"
        );

        Trip trip = Trip.builder()
                .routePlan(routePlan)
                .handlingClassLock("FRAGILE")
                .overrideReason("Dispatcher approved isolated loading")
                .build();

        assertEquals(routePlan, trip.getRoutePlan());
        assertEquals("FRAGILE", trip.getHandlingClassLock());
        assertEquals("Dispatcher approved isolated loading", trip.getOverrideReason());
    }
}
