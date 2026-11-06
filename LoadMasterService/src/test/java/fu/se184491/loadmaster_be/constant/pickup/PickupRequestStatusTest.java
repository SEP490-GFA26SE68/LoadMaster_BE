package fu.se184491.loadmaster_be.constant.pickup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupRequestStatusTest {

    @Test
    void followsTheApprovalFlow() {
        assertTrue(PickupRequestStatus.PENDING.canMoveTo(PickupRequestStatus.VALIDATED));
        assertTrue(PickupRequestStatus.PENDING.canMoveTo(PickupRequestStatus.APPROVED));
        assertTrue(PickupRequestStatus.VALIDATED.canMoveTo(PickupRequestStatus.REJECTED));
        assertTrue(PickupRequestStatus.APPROVED.canMoveTo(PickupRequestStatus.LOADED));
        assertTrue(PickupRequestStatus.LOADED.canMoveTo(PickupRequestStatus.DELIVERED));
    }

    @Test
    void finalStatesAndSkipsAreRejected() {
        assertFalse(PickupRequestStatus.REJECTED.canMoveTo(PickupRequestStatus.APPROVED));
        assertFalse(PickupRequestStatus.DELIVERED.canMoveTo(PickupRequestStatus.LOADED));
        assertFalse(PickupRequestStatus.APPROVED.canMoveTo(PickupRequestStatus.DELIVERED));
        assertFalse(PickupRequestStatus.LOADED.open());
        assertTrue(PickupRequestStatus.VALIDATED.open());
    }
}
