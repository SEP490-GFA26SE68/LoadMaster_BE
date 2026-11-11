package fu.se184491.loadmaster_be.service.trip;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StopReorderRulesTest {

    private static final List<Long> CURRENT = List.of(1L, 2L, 3L, 4L);

    @Test
    void proposedOrderMustBeADifferentPermutation() {
        assertThat(StopReorderRules.checkProposedOrder(CURRENT, CURRENT, 0, List.of()).code())
                .isEqualTo("STOP_ORDER_INVALID");
        assertThat(StopReorderRules.checkProposedOrder(CURRENT, List.of(1L, 2L, 3L), 0, List.of()).code())
                .isEqualTo("STOP_ORDER_INVALID");
        assertThat(StopReorderRules.checkProposedOrder(CURRENT, List.of(1L, 2L, 3L, 3L), 0, List.of()).code())
                .isEqualTo("STOP_ORDER_INVALID");
        assertThat(StopReorderRules.checkProposedOrder(CURRENT, List.of(1L, 2L, 4L, 3L), 1, List.of())).isNull();
    }

    @Test
    void finishedOrReachedStopsStayInPlace() {
        StopReorderRules.Violation moved =
                StopReorderRules.checkProposedOrder(CURRENT, List.of(2L, 1L, 3L, 4L), 1, List.of());

        assertThat(moved.code()).isEqualTo("STOP_NOT_MOVABLE");
        assertThat(moved.stopIds()).containsExactly(1L);
    }

    @Test
    void pickupMustStayBeforeItsDelivery() {
        List<StopReorderRules.PickupPair> pickups = List.of(new StopReorderRules.PickupPair(2L, 3L));

        StopReorderRules.Violation late =
                StopReorderRules.checkProposedOrder(CURRENT, List.of(1L, 3L, 2L, 4L), 1, pickups);

        assertThat(late.code()).isEqualTo("PICKUP_AFTER_DELIVERY");
        assertThat(late.stopIds()).containsExactly(2L, 3L);
        assertThat(StopReorderRules.checkProposedOrder(CURRENT, List.of(1L, 2L, 4L, 3L), 1, pickups)).isNull();
    }

    @Test
    void laterBoxRightBehindAnEarlierOneBlocksItFully() {
        // Cửa sau ở X lớn: kiện của điểm 3 nằm hẳn giữa kiện của điểm 4 và cửa
        StopReorderRules.Cargo deep = new StopReorderRules.Cargo("A", "PK-A", 4L, 0, 0, 0, 100, 100, 100);
        StopReorderRules.Cargo nearDoor = new StopReorderRules.Cargo("B", "PK-B", 3L, 100, 0, 0, 100, 100, 100);

        StopReorderRules.Unloading before = StopReorderRules.checkUnloading(List.of(deep, nearDoor), CURRENT);
        StopReorderRules.Unloading after =
                StopReorderRules.checkUnloading(List.of(deep, nearDoor), List.of(1L, 2L, 4L, 3L));

        assertThat(before.blocked()).isEmpty();
        assertThat(after.blocked()).isEqualTo(Set.of("A"));
        assertThat(after.partial()).isEmpty();
    }

    @Test
    void halfCoveredRearIsOnlyAPartialBlock() {
        StopReorderRules.Cargo deep = new StopReorderRules.Cargo("A", "PK-A", 3L, 0, 0, 0, 100, 100, 100);
        StopReorderRules.Cargo half = new StopReorderRules.Cargo("B", "PK-B", 4L, 120, 50, 0, 80, 50, 100);

        StopReorderRules.Unloading result = StopReorderRules.checkUnloading(List.of(deep, half), CURRENT);

        assertThat(result.blocked()).isEmpty();
        assertThat(result.partial()).isEqualTo(Set.of("A"));
    }

    @Test
    void twoBoxesTogetherCanCoverTheWholeRear() {
        StopReorderRules.Cargo deep = new StopReorderRules.Cargo("A", "PK-A", 3L, 0, 0, 0, 100, 100, 100);
        StopReorderRules.Cargo left = new StopReorderRules.Cargo("B", "PK-B", 4L, 100, 0, 0, 50, 50, 100);
        StopReorderRules.Cargo right = new StopReorderRules.Cargo("C", "PK-C", 4L, 100.1, 50, 0, 50, 50.2, 100);

        StopReorderRules.Unloading result = StopReorderRules.checkUnloading(List.of(deep, left, right), CURRENT);

        assertThat(result.blocked()).isEqualTo(Set.of("A"));
    }
}
