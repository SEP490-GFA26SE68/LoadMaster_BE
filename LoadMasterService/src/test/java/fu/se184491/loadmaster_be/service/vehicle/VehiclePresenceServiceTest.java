package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every presence state of the loading trip's vehicle, against the guards of the physical verification. */
@ExtendWith(MockitoExtension.class)
class VehiclePresenceServiceTest {
    static final long COMPANY = 1, WAREHOUSE = 101, OTHER = 102, TRIP = 90, VEHICLE = 6, WORKER = 7;

    @Mock VehiclePresenceStore store;
    @Mock CurrentUserService current;
    @InjectMocks VehiclePresenceService service;

    final UUID key = UUID.randomUUID();
    final String reference = "loading-trip:" + TRIP + ":" + key;
    final Map<String, Object> trip = new HashMap<>(Map.of("status", "LOADING", "origin_warehouse_id", WAREHOUSE, "vehicle_id", VEHICLE));
    final Map<String, Object> vehicle = new HashMap<>(Map.of("id", VEHICLE, "status", "AVAILABLE"));

    @BeforeEach
    void setUp() {
        lenient().when(current.getCurrentUser()).thenReturn(User.builder().id(WORKER).company(Company.builder().id(COMPANY).build())
                .userRoleType(UserRole.WAREHOUSE_WORKER).status(UserStatus.ACTIVE).build());
        lenient().when(store.warehouse(WAREHOUSE, COMPANY)).thenReturn(true);
        lenient().when(store.assigned(WORKER, COMPANY, WAREHOUSE)).thenReturn(true);
        lenient().when(store.lockTrip(TRIP, COMPANY)).thenReturn(trip);
        lenient().when(store.lockVehicle(VEHICLE, COMPANY)).thenReturn(vehicle);
        lenient().when(store.lockAssignment(WORKER, COMPANY, WAREHOUSE)).thenReturn(true);
        // Mockito answers an empty map for Map and 0 for Long; "none" is null here.
        lenient().when(store.loadingTripFrom(VEHICLE, COMPANY, WAREHOUSE)).thenReturn(null);
        lenient().when(store.receipt(COMPANY, key.toString())).thenReturn(null);
    }

    static VehiclePresenceStore.Presence presence(String status, Long warehouse) {
        return new VehiclePresenceStore.Presence(VEHICLE, COMPANY, warehouse, status, null, null, null, 1);
    }

    void verifyNoPresenceWrite() {
        verify(store, never()).change(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString());
        verify(store, never()).receipt(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void unknownIsCheckedInAtTheOriginAfterLockingTripThenVehicle() {
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("UNKNOWN", null), presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals("AT_WAREHOUSE", service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key).status());
        var order = inOrder(store);
        order.verify(store).lockTrip(TRIP, COMPANY);
        order.verify(store).lockRequest(COMPANY, key.toString());
        order.verify(store).lockVehicle(VEHICLE, COMPANY);
        order.verify(store).change(VEHICLE, COMPANY, WAREHOUSE, "AT_WAREHOUSE", WORKER, reference, "CHECK_IN");
        order.verify(store).receipt(COMPANY, key.toString(), VEHICLE, WAREHOUSE, WORKER);
        verify(store, never()).change(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.eq("CHECK_OUT"));
    }

    @Test
    void inTransitAfterAnEarlierTripIsCheckedInAtTheOrigin() {
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("IN_TRANSIT", null), presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals(WAREHOUSE, service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key).warehouseId());
        verify(store).change(VEHICLE, COMPANY, WAREHOUSE, "AT_WAREHOUSE", WORKER, reference, "CHECK_IN");
        verify(store).receipt(COMPANY, key.toString(), VEHICLE, WAREHOUSE, WORKER);
    }

    @Test
    void atAnotherWarehouseIsCheckedOutThereThenCheckedInAtTheOrigin() {
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("AT_WAREHOUSE", OTHER), presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals(WAREHOUSE, service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key).warehouseId());
        var order = inOrder(store);
        order.verify(store).change(VEHICLE, COMPANY, OTHER, "IN_TRANSIT", WORKER, reference + ":out", "CHECK_OUT");
        order.verify(store).change(VEHICLE, COMPANY, WAREHOUSE, "AT_WAREHOUSE", WORKER, reference, "CHECK_IN");
        order.verify(store).receipt(COMPANY, key.toString(), VEHICLE, WAREHOUSE, WORKER);
    }

    @Test
    void alreadyAtTheOriginWritesNoEventButKeepsTheReceipt() {
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals("AT_WAREHOUSE", service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key).status());
        verify(store, never()).change(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString());
        verify(store).receipt(COMPANY, key.toString(), VEHICLE, WAREHOUSE, WORKER);
    }

    @Test
    void replayOfANoOpConfirmationSucceedsWhileTheVehicleIsAtTheOrigin() {
        when(store.receipt(COMPANY, key.toString())).thenReturn(Map.of("vehicle_id", VEHICLE, "warehouse_id", WAREHOUSE, "actor_user_id", WORKER));
        when(store.event(COMPANY, reference)).thenReturn(Optional.empty());
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals("AT_WAREHOUSE", service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key).status());
        verifyNoPresenceWrite();
    }

    @Test
    void replayWithoutEventAfterTheVehicleLeftIsAConflict() {
        when(store.receipt(COMPANY, key.toString())).thenReturn(Map.of("vehicle_id", VEHICLE, "warehouse_id", WAREHOUSE, "actor_user_id", WORKER));
        when(store.event(COMPANY, reference)).thenReturn(Optional.empty());
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("IN_TRANSIT", null));
        assertCode(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        verifyNoPresenceWrite();
    }

    @Test
    void keyOfAnotherVehicleOrActorIsAConflict() {
        when(store.receipt(COMPANY, key.toString())).thenReturn(Map.of("vehicle_id", 2L, "warehouse_id", WAREHOUSE, "actor_user_id", WORKER));
        assertCode(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        when(store.receipt(COMPANY, key.toString())).thenReturn(Map.of("vehicle_id", VEHICLE, "warehouse_id", WAREHOUSE, "actor_user_id", 99L));
        assertCode(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        verifyNoPresenceWrite();
    }

    @Test
    void activeWarehouseTransferIsATransferConflict() {
        when(store.activeTransfer(VEHICLE)).thenReturn(true);
        assertCode(ErrorCode.VEHICLE_TRANSFER_CONFLICT);
        verifyNoPresenceWrite();
    }

    @Test
    void anotherActiveTripOrUnavailableVehicleIsBusy() {
        when(store.tripBusy(VEHICLE, TRIP)).thenReturn(true);
        assertCode(ErrorCode.VEHICLE_BUSY);
        lenient().when(store.tripBusy(VEHICLE, TRIP)).thenReturn(false);
        vehicle.put("status", "MAINTENANCE");
        assertCode(ErrorCode.VEHICLE_BUSY);
        verifyNoPresenceWrite();
    }

    @Test
    void tripMustBeLoadingFromThisWarehouseWithTheSelectedVehicle() {
        trip.put("origin_warehouse_id", OTHER);
        assertCode(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        trip.put("origin_warehouse_id", WAREHOUSE);
        trip.put("status", "PLANNED");
        assertCode(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        trip.put("status", "LOADING");
        assertEquals(ErrorCode.VEHICLE_PRESENCE_CONFLICT,
                assertThrows(AppException.class, () -> service.confirmLoadingPresence(WAREHOUSE, TRIP, 2, key)).getErrorCode());
        verify(store, never()).lockVehicle(anyLong(), anyLong());
        verifyNoPresenceWrite();
    }

    @Test
    void workerMustStillBeAssignedToTheWarehouse() {
        when(store.assigned(WORKER, COMPANY, WAREHOUSE)).thenReturn(false);
        assertCode(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        when(store.assigned(WORKER, COMPANY, WAREHOUSE)).thenReturn(true);
        when(store.lockAssignment(WORKER, COMPANY, WAREHOUSE)).thenReturn(false);
        assertCode(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        verifyNoPresenceWrite();
    }

    @Test
    void checkInOfTheLoadingVehicleAtItsOriginUsesTheLoadingVerification() {
        when(store.loadingTripFrom(VEHICLE, COMPANY, WAREHOUSE)).thenReturn(TRIP);
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("IN_TRANSIT", null), presence("AT_WAREHOUSE", WAREHOUSE));
        assertEquals("AT_WAREHOUSE", service.checkIn(WAREHOUSE, VEHICLE, key).status());
        var order = inOrder(store);
        order.verify(store).lockTrip(TRIP, COMPANY);
        order.verify(store).lockVehicle(VEHICLE, COMPANY);
        order.verify(store).change(VEHICLE, COMPANY, WAREHOUSE, "AT_WAREHOUSE", WORKER, reference, "CHECK_IN");
        verify(store, never()).busy(anyLong(), any());
    }

    @Test
    void checkInOfAVehicleBusyWithAnotherTripStaysRejected() {
        when(store.busy(VEHICLE, null)).thenReturn(true);
        assertEquals(ErrorCode.VEHICLE_BUSY, assertThrows(AppException.class, () -> service.checkIn(WAREHOUSE, VEHICLE, key)).getErrorCode());
        verify(store, never()).lockTrip(anyLong(), anyLong());
        verifyNoPresenceWrite();
    }

    Trip departing() {
        return Trip.builder().id(TRIP).company(Company.builder().id(COMPANY).build()).vehicle(Vehicle.builder().id(VEHICLE).build())
                .originWarehouse(CompanyWarehouse.builder().id(WAREHOUSE).build()).build();
    }

    @Test
    void departureNeedsTheVehicleAtTheOriginAndPointsTheDriverToTheWarehouseCheck() {
        for (var p : new VehiclePresenceStore.Presence[]{presence("UNKNOWN", null), presence("IN_TRANSIT", null), presence("AT_WAREHOUSE", OTHER)}) {
            when(store.presence(VEHICLE, COMPANY)).thenReturn(p);
            assertEquals(ErrorCode.DRIVER_VEHICLE_PRESENCE_UNVERIFIED,
                    assertThrows(AppException.class, () -> service.departure(departing(), 8)).getErrorCode(), p.status());
        }
        when(store.presence(VEHICLE, COMPANY)).thenReturn(presence("AT_WAREHOUSE", WAREHOUSE));
        when(store.busy(VEHICLE, TRIP)).thenReturn(true);
        assertEquals(ErrorCode.DRIVER_VEHICLE_DEPARTURE_BLOCKED, assertThrows(AppException.class, () -> service.departure(departing(), 8)).getErrorCode());
        when(store.busy(VEHICLE, TRIP)).thenReturn(false);
        verify(store, never()).change(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString());
        service.departure(departing(), 8);
        verify(store).change(VEHICLE, COMPANY, WAREHOUSE, "IN_TRANSIT", 8, "trip:" + TRIP + ":departure", "CHECK_OUT");
    }

    void assertCode(ErrorCode code) {
        assertEquals(code, assertThrows(AppException.class, () -> service.confirmLoadingPresence(WAREHOUSE, TRIP, VEHICLE, key)).getErrorCode());
    }
}
