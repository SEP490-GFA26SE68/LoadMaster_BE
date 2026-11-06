package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator.CenterOfGravity;
import fu.se184491.loadmaster_be.service.pickup.PickupRoutePath.Point;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupRulesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 6, 8, 0);
    private static final Point POSITION = new Point(10.80, 106.70);
    private static final PickupRules.VehicleLimits VEHICLE =
            new PickupRules.VehicleLimits(1000, 600, 240, 240, null, null, null);
    private static final List<PickupRules.RouteStop> STOPS = List.of(
            new PickupRules.RouteStop("31", new Point(10.80, 106.75), false, 5, true),
            new PickupRules.RouteStop("32", new Point(10.80, 106.80), false, 3, true));

    private static FreedSpacePacker.Result packing(CenterOfGravity before, CenterOfGravity after) {
        return new FreedSpacePacker.Result(List.of(), List.of(), new FreedSpacePacker.Snapshot(0, before, null),
                new FreedSpacePacker.Snapshot(0, after, null), 0, 1_000_000);
    }

    private static PickupRules.Cargo cargo(double kg, String handlingClass) {
        return new PickupRules.Cargo(50, 50, 50, kg, handlingClass);
    }

    private static PickupRules.Context context(Point pickup, Point delivery, LocalDateTime deadline, List<PickupRules.Cargo> cargo,
                                               double onboardKg, String locked, String reason, FreedSpacePacker.Result packing) {
        return new PickupRules.Context(new PickupRules.Request(pickup, delivery, deadline, cargo), VEHICLE, POSITION, AT, STOPS,
                onboardKg, packing, locked, reason, (a, b) -> 600);
    }

    private static PickupRules.Context ok() {
        return context(new Point(10.80, 106.72), new Point(10.80, 106.77), AT.plusHours(2),
                List.of(cargo(50, "STANDARD")), 900, "STANDARD", null, packing(null, null));
    }

    private static String code(PickupRules.Context context, int rule) {
        return PickupRules.evaluate(context).get(rule - 1).code();
    }

    @Test
    void requestAlongTheRoutePassesAllTenRules() {
        List<PickupRules.RuleResult> results = PickupRules.evaluate(ok());

        assertEquals(10, results.size());
        assertTrue(PickupRules.allPassed(results), results.toString());
        assertEquals("PICKUP_ON_ROUTE", results.get(0).code());
        assertEquals("PICKUP_AXLE_UNAVAILABLE", results.get(4).code());
    }

    @Test
    void pickupFarFromTheRouteOrBehindTheVehicleFailsRuleOne() {
        var far = context(new Point(11.00, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, null)), 0, null, null,
                packing(null, null));
        var behind = context(new Point(10.80, 106.65), new Point(10.80, 106.77), null, List.of(cargo(5, null)), 0, null, null,
                packing(null, null));

        assertEquals("PICKUP_OFF_ROUTE", code(far, 1));
        assertEquals("PICKUP_BEHIND_VEHICLE", code(behind, 1));
    }

    @Test
    void deliveryBeyondTheProtectedStopFailsRuleTwo() {
        var beyond = context(new Point(10.80, 106.72), new Point(10.80, 106.85), null, List.of(cargo(5, null)), 0, null, null,
                packing(null, null));

        assertEquals("PICKUP_DELIVERY_BEYOND_PROTECTED", code(beyond, 2));
        assertEquals("PICKUP_DELIVERY_IN_RANGE", code(ok(), 2));
    }

    @Test
    void payloadOverTheLimitFailsRuleThree() {
        var heavy = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(200, "STANDARD")), 900,
                "STANDARD", null, packing(null, null));

        PickupRules.RuleResult result = PickupRules.evaluate(heavy).get(2);
        assertFalse(result.passed());
        assertEquals("PICKUP_PAYLOAD_EXCEEDED", result.code());
        assertEquals(100.0, result.params().get("overKg"));
    }

    @Test
    void otherHandlingClassNeedsAReason() {
        var conflict = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, "FRAGILE")), 0,
                "STANDARD", null, packing(null, null));
        var overridden = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, "FRAGILE")), 0,
                "STANDARD", "Khách cần gấp", packing(null, null));

        assertEquals("PICKUP_CLASS_CONFLICT", code(conflict, 8));
        assertEquals("PICKUP_CLASS_OVERRIDDEN", code(overridden, 8));
        assertTrue(PickupRules.evaluate(overridden).get(7).passed());
    }

    @Test
    void deadlineRuleWalksTheRouteAfterInsertion() {
        // vị trí → điểm hiện tại → điểm nhận → điểm giao: ba chặng 10 phút, tới lúc 08:30
        var missed = context(new Point(10.80, 106.72), new Point(10.80, 106.77), AT.plusMinutes(10), List.of(cargo(5, null)), 0,
                null, null, packing(null, null));
        var none = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, null)), 0, null, null,
                packing(null, null));

        PickupRules.RuleResult result = PickupRules.evaluate(missed).get(8);
        assertEquals("PICKUP_DEADLINE_MISSED", result.code());
        assertEquals("2026-11-06T08:30:00", result.params().get("eta"));
        assertEquals("PICKUP_DEADLINE_OK", code(ok(), 9));
        assertEquals("PICKUP_NO_DEADLINE", code(none, 9));
    }

    @Test
    void centerOfGravityOnlyFailsOnNewKindsOfOffset() {
        var stillFront = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, null)), 0, null, null,
                packing(new CenterOfGravity(100, 120, 50), new CenterOfGravity(120, 120, 50)));
        var nowRear = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null, List.of(cargo(5, null)), 0, null, null,
                packing(new CenterOfGravity(300, 120, 50), new CenterOfGravity(550, 120, 50)));

        assertEquals("PICKUP_COG_NOT_WORSE", code(stillFront, 6));
        assertEquals("PICKUP_COG_OFF_CENTER", code(nowRear, 6));
    }

    @Test
    void packingResultDrivesSpaceStackingAndBlockingRules() {
        var packed = new FreedSpacePacker.Result(List.of(),
                List.of(new FreedSpacePacker.Unplaced(0, "NO_SPACE"), new FreedSpacePacker.Unplaced(1, "STACKING_VIOLATION")),
                new FreedSpacePacker.Snapshot(0, null, null), new FreedSpacePacker.Snapshot(0, null, null), 2, 0);
        var context = context(new Point(10.80, 106.72), new Point(10.80, 106.77), null,
                List.of(cargo(5, null), cargo(5, null)), 0, null, null, packed);

        assertEquals("PICKUP_FREED_SPACE_INSUFFICIENT", code(context, 4));
        assertEquals("PICKUP_FRAGILE_STACKED", code(context, 7));
        assertEquals("PICKUP_BLOCKS_CARGO", code(context, 10));
    }

    @Test
    void deliveryAtAnUpcomingStopReusesIt() {
        PickupRules.Insertion reuse = PickupRules.insertion(STOPS, new Point(10.80, 106.80));
        PickupRules.Insertion fresh = PickupRules.insertion(STOPS, new Point(10.80, 106.77));

        assertEquals(0, reuse.currentIndex());
        assertEquals(1, reuse.protectedIndex());
        assertEquals(1, reuse.reusedIndex());
        assertEquals(-1, fresh.reusedIndex());
    }
}
