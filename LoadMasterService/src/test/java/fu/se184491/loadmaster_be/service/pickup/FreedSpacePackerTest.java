package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreedSpacePackerTest {

    private static FreedSpacePacker.Vehicle truck(Double doorWidth, Double doorHeight) {
        return new FreedSpacePacker.Vehicle(600, 240, 240, doorWidth, doorHeight, List.of(), null);
    }

    private static FreedSpacePacker.Item item(int index, double l, double w, double h, double kg, boolean fragile) {
        return new FreedSpacePacker.Item(index, l, w, h, kg, fragile);
    }

    @Test
    void emptyTruckPlacesPickupOnFloorNextToDoor() {
        var result = FreedSpacePacker.pack(truck(null, null), List.of(),
                List.of(item(0, 100, 100, 100, 50, false)), 1000, 1);

        assertEquals(1, result.placements().size());
        assertTrue(result.unplaced().isEmpty());
        var placement = result.placements().getFirst();
        assertEquals(500, placement.x(), 1e-6);
        assertEquals(0, placement.y(), 1e-6);
        assertEquals(0, placement.z(), 1e-6);
        assertEquals(0, result.blockedCount());
        assertEquals(50, result.after().totalKg(), 1e-6);
        assertEquals(0, result.before().totalKg(), 1e-6);
    }

    @Test
    void cargoThatCannotPassTheDoorStaysOff() {
        var result = FreedSpacePacker.pack(truck(100.0, 100.0), List.of(),
                List.of(item(0, 200, 200, 200, 10, false)), 1000, 1);

        assertEquals("DOOR_TOO_SMALL", result.unplaced().getFirst().reasonCode());
    }

    @Test
    void cargoHeavierThanRemainingPayloadStaysOff() {
        var result = FreedSpacePacker.pack(truck(null, null), List.of(),
                List.of(item(0, 50, 50, 50, 50, false)), 10, 1);

        assertEquals("OVER_PAYLOAD", result.unplaced().getFirst().reasonCode());
    }

    @Test
    void fullTruckHasNoSpace() {
        var full = new FreedSpacePacker.Onboard(new Box(1L, 0, 0, 0, 600, 240, 240, 1000), 1);
        var result = FreedSpacePacker.pack(truck(null, null), List.of(full),
                List.of(item(0, 50, 50, 50, 5, false)), 1000, 2);

        assertEquals("NO_SPACE", result.unplaced().getFirst().reasonCode());
        assertEquals(0, result.freeVolumeCm3(), 1e-6);
    }

    @Test
    void pickupNeverRestsOnFragilePickup() {
        var onboard = new FreedSpacePacker.Onboard(new Box(1L, 0, 0, 0, 500, 240, 240, 1000), 1);
        var result = FreedSpacePacker.pack(truck(null, null), List.of(onboard),
                List.of(item(0, 100, 240, 120, 10, true), item(1, 100, 240, 120, 10, false)), 1000, 2);

        assertEquals(1, result.placements().size());
        assertEquals(0, result.placements().getFirst().index());
        assertEquals("STACKING_VIOLATION", result.unplaced().getFirst().reasonCode());
    }

    @Test
    void pickupDeliveredLaterThatHidesOnboardCargoCountsAsBlocking() {
        var onboard = new FreedSpacePacker.Onboard(new Box(1L, 0, 0, 0, 100, 240, 240, 100), 1);
        var result = FreedSpacePacker.pack(truck(null, null), List.of(onboard),
                List.of(item(0, 100, 240, 240, 10, false)), 1000, 2);

        assertEquals(1, result.blockedCount());
    }

    @Test
    void pickupDeliveredFirstDoesNotBlock() {
        var onboard = new FreedSpacePacker.Onboard(new Box(1L, 0, 0, 0, 100, 240, 240, 100), 3);
        var result = FreedSpacePacker.pack(truck(null, null), List.of(onboard),
                List.of(item(0, 100, 240, 240, 10, false)), 1000, 2);

        assertEquals(0, result.blockedCount());
    }
}
