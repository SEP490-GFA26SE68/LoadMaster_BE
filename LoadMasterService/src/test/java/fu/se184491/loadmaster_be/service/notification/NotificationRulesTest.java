package fu.se184491.loadmaster_be.service.notification;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRulesTest {

    private static final NotificationRules.Viewer DISPATCHER = new NotificationRules.Viewer(10L, UserRole.DISPATCHER);
    private static final NotificationRules.Viewer MANAGER = new NotificationRules.Viewer(11L, UserRole.MANAGER);
    private static final NotificationRules.Viewer WORKER = new NotificationRules.Viewer(12L, UserRole.WAREHOUSE_WORKER);
    private static final NotificationRules.Viewer DRIVER = new NotificationRules.Viewer(13L, UserRole.DRIVER);
    private static final NotificationRules.Viewer ADMIN = new NotificationRules.Viewer(14L, UserRole.ADMIN);

    private static NotificationRules.Event event(String action, Long actor, Map<String, Object> before,
                                                 Map<String, Object> after, Long tripDriver) {
        return new NotificationRules.Event(action, actor, before, after, tripDriver);
    }

    @Test
    void ownActionsNeverNotify() {
        assertThat(NotificationRules.concerns(event("PLAN_APPROVED", 10L, null, null, null), DISPATCHER)).isFalse();
        assertThat(NotificationRules.concerns(event("PLAN_APPROVED", 20L, null, null, null), DISPATCHER)).isTrue();
    }

    @Test
    void eachRoleOnlyGetsItsActions() {
        NotificationRules.Event approved = event("PLAN_APPROVED", 20L, null, null, null);
        assertThat(NotificationRules.concerns(approved, MANAGER)).isFalse();
        assertThat(NotificationRules.concerns(event("TRIP_EXCEPTION_ESCALATED", null, null, null, null), MANAGER)).isTrue();
        assertThat(NotificationRules.concerns(event("DELIVERY_ETA_RISK", null, null, null, null), MANAGER)).isFalse();
        assertThat(NotificationRules.concerns(event("DELIVERY_ETA_RISK", null, null, null, null), DISPATCHER)).isTrue();
        assertThat(NotificationRules.concerns(event("CREDIT_LOW_BALANCE", null, null, null, null), ADMIN)).isTrue();
        assertThat(NotificationRules.concerns(event("CREDIT_LOW_BALANCE", null, null, null, null), DISPATCHER)).isFalse();
        assertThat(NotificationRules.actionsFor(UserRole.SYSTEM_MANAGER)).isEmpty();
        assertThat(NotificationRules.actionsFor(UserRole.SYSTEM_SUPPORTER)).isEmpty();
        assertThat(NotificationRules.actionsFor(UserRole.SYSTEM_ADMIN)).contains("USER_LOCKED").doesNotContain("TRIP_CANCELLED");
    }

    @Test
    void warehouseOnlyHearsTripsCancelledWhileLoading() {
        assertThat(NotificationRules.concerns(
                event("TRIP_CANCELLED", 10L, Map.of("status", "LOADING"), Map.of(), null), WORKER)).isTrue();
        assertThat(NotificationRules.concerns(
                event("TRIP_CANCELLED", 10L, Map.of("status", "PLANNED"), Map.of(), null), WORKER)).isFalse();
    }

    @Test
    void driverOnlyHearsOwnTripsOnceTheWarehouseStarted() {
        NotificationRules.Event started = event("TRIP_STATUS_TRANSITION", 12L,
                Map.of("status", "PLANNED"), Map.of("status", "LOADING", "driverId", 13L), null);
        assertThat(NotificationRules.concerns(started, DRIVER)).isTrue();
        NotificationRules.Event otherDriver = event("TRIP_STATUS_TRANSITION", 12L,
                Map.of("status", "PLANNED"), Map.of("status", "LOADING", "driverId", 99L), 13L);
        assertThat(NotificationRules.concerns(otherDriver, DRIVER)).as("tài xế ghi lúc xảy ra thắng").isFalse();
        NotificationRules.Event delivered = event("TRIP_STATUS_TRANSITION", 13L,
                Map.of("status", "IN_TRANSIT"), Map.of("status", "DELIVERED"), 13L);
        assertThat(NotificationRules.concerns(delivered, DRIVER)).isFalse();
        // Bản ghi cũ không ghi tài xế: lấy tài xế hiện tại của chuyến
        assertThat(NotificationRules.concerns(event("LOADING_COMPLETED", 12L, null, Map.of("tripId", 5L), 13L), DRIVER))
                .isTrue();
        assertThat(NotificationRules.concerns(
                event("TRIP_CANCELLED", 10L, Map.of("status", "PLANNED"), Map.of("driverId", 13L), 13L), DRIVER))
                .as("huỷ lúc còn lập kế hoạch không báo tài xế").isFalse();
        assertThat(NotificationRules.concerns(
                event("TRIP_CANCELLED", 10L, Map.of("status", "LOADING"), Map.of("driverId", 13L), 13L), DRIVER))
                .isTrue();
    }

    @Test
    void driverChangeNotifiesNewAndPreviousDriverOnlyAfterLoadingStarted() {
        NotificationRules.Event change = event("TRIP_UPDATED", 10L,
                Map.of("driverId", "13", "tripStatus", "LOADING"), Map.of("driverId", "30", "tripStatus", "LOADING"), 30L);
        assertThat(NotificationRules.concerns(change, DRIVER)).isTrue();
        assertThat(NotificationRules.concerns(change, new NotificationRules.Viewer(30L, UserRole.DRIVER))).isTrue();
        assertThat(NotificationRules.concerns(change, new NotificationRules.Viewer(31L, UserRole.DRIVER))).isFalse();
        NotificationRules.Event planning = event("TRIP_UPDATED", 10L,
                Map.of("driverId", "13", "tripStatus", "PLANNED"), Map.of("driverId", "30", "tripStatus", "PLANNED"), 30L);
        assertThat(NotificationRules.concerns(planning, DRIVER)).isFalse();
    }

    @Test
    void personalEventsFollowTheRequester() {
        NotificationRules.Event rejected = event("MANUAL_CONFIRM_REJECTED", 10L, null,
                Map.of("requestedBy", 13L), 13L);
        assertThat(NotificationRules.concerns(rejected, DRIVER)).isTrue();
        assertThat(NotificationRules.concerns(event("MANUAL_CONFIRM_REJECTED", 10L, null,
                Map.of("requestedBy", 40L), 13L), DRIVER)).isFalse();

        NotificationRules.Event ticket = event("SUPPORT_TICKET_UPDATED", 50L, null, Map.of("requestedBy", 11L), null);
        assertThat(NotificationRules.concerns(ticket, MANAGER)).isTrue();
        assertThat(NotificationRules.concerns(ticket, DISPATCHER)).isFalse();
    }

    @Test
    void pickupRequestsReachDispatchersAndDecisionsReachTheTripDriver() {
        NotificationRules.Event requested = event("PICKUP_REQUESTED", 13L, null, Map.of("tripId", 5L), 13L);
        assertThat(NotificationRules.concerns(requested, DISPATCHER)).isTrue();
        assertThat(NotificationRules.concerns(event("PICKUP_REQUESTED", 10L, null, Map.of(), 13L), DISPATCHER))
                .as("điều phối viên tự gửi thì không báo chính mình").isFalse();
        assertThat(NotificationRules.concerns(requested, MANAGER)).isFalse();
        assertThat(NotificationRules.concerns(requested, DRIVER)).isFalse();

        NotificationRules.Event approved = event("PICKUP_APPROVED", 10L, null, Map.of("driverId", 13L), 13L);
        assertThat(NotificationRules.concerns(approved, DRIVER)).isTrue();
        assertThat(NotificationRules.concerns(approved, new NotificationRules.Viewer(30L, UserRole.DRIVER))).isFalse();
        assertThat(NotificationRules.concerns(approved, DISPATCHER)).isFalse();
        assertThat(NotificationRules.concerns(
                event("PICKUP_REJECTED", 10L, null, Map.of("driverId", 13L, "reason", "Xe đầy"), 13L), DRIVER)).isTrue();
        assertThat(NotificationRules.concerns(event("PICKUP_REJECTED", 10L, null, Map.of(), 13L), DRIVER))
                .as("không ghi tài xế thì không báo ai").isFalse();
        assertThat(NotificationRules.concerns(event("PICKUP_LOADED", 13L, null, Map.of(), 13L), DISPATCHER)).isFalse();
    }

    @Test
    void tripUpdateWithoutStatusIsNotTheDriversAndDoesNotThrow() {
        assertThat(NotificationRules.concerns(event("TRIP_UPDATED", 10L, Map.of(), Map.of("driverId", 13L), 13L), DRIVER)).isFalse();
        assertThat(NotificationRules.concerns(event("TRIP_UPDATED", 10L, null, null, 13L), DRIVER)).isFalse();
    }
}
