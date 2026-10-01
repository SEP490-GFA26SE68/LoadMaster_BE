package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripStatusContractTest {

    @Test
    void tripStatusExposesThePlanningAndDeliveryFlow() {
        assertEquals(
                List.of("DRAFT", "PLANNED", "LOADING", "IN_TRANSIT", "DELIVERED", "CANCELLED"),
                List.of(TripStatus.values()).stream().map(Enum::name).toList()
        );
    }

    @Test
    void deliveryStopStatusExposesTheArrivalFlow() {
        assertEquals(
                List.of("PENDING", "ARRIVED", "COMPLETED"),
                List.of(DeliveryStopStatus.values()).stream().map(Enum::name).toList()
        );
    }
}
