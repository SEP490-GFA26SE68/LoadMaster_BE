package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryStopEntityContractTest {

    @Test
    void newDeliveryStopStartsPending() {
        assertEquals(DeliveryStopStatus.PENDING, DeliveryStop.builder().build().getStatus());
    }

    @Test
    void deliveryStopExposesCoordinatesAndArrivalTimes() {
        BigDecimal latitude = new BigDecimal("10.7626220");
        BigDecimal longitude = new BigDecimal("106.6601720");
        LocalDateTime plannedArrival = LocalDateTime.of(2026, 10, 1, 9, 30);
        LocalDateTime actualArrival = LocalDateTime.of(2026, 10, 1, 9, 42);

        DeliveryStop stop = DeliveryStop.builder()
                .latitude(latitude)
                .longitude(longitude)
                .plannedArrival(plannedArrival)
                .actualArrival(actualArrival)
                .build();

        assertAll(
                () -> assertEquals(latitude, stop.getLatitude()),
                () -> assertEquals(longitude, stop.getLongitude()),
                () -> assertEquals(plannedArrival, stop.getPlannedArrival()),
                () -> assertEquals(actualArrival, stop.getActualArrival())
        );
    }
}
