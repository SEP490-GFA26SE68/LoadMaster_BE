package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.request.vehicle.UpdateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleDeleteResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VehicleServiceImplTest {

    private static final List<TripStatus> RUNNING = List.of(TripStatus.LOADING, TripStatus.IN_TRANSIT);
    private static final List<TripStatus> PLANNING = List.of(TripStatus.DRAFT, TripStatus.PLANNED);

    @Mock private VehicleRepository vehicleRepository;
    @Mock private VehicleTypeRepository vehicleTypeRepository;
    @Mock private TripRepository tripRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private LoadPlanRepository loadPlanRepository;
    @InjectMocks private VehicleServiceImpl service;

    private VehicleType box;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).companyName("Long Bình").build();
        box = VehicleType.builder().id(3L).company(company).name("Thùng 6 m").build();
        vehicle = Vehicle.builder().id(5L).company(company).vehicleType(box).name("Xe 01")
                .licensePlate("51C-123.45").status(VehicleStatus.AVAILABLE).build();
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(vehicleRepository.findByIdAndCompanyId(5L, 1L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(call -> call.getArgument(0));
        when(tripRepository.findByVehicleIdAndStatusIn(anyLong(), any())).thenReturn(List.of());
    }

    @Test
    void changingTheTypeMarksPlansOfUnstartedTripsStale() {
        VehicleType longer = VehicleType.builder().id(4L).company(vehicle.getCompany()).name("Thùng 8 m").build();
        when(vehicleTypeRepository.findByIdAndCompanyId(4L, 1L)).thenReturn(Optional.of(longer));
        when(tripRepository.findByVehicleIdAndStatusIn(5L, PLANNING))
                .thenReturn(List.of(Trip.builder().id(40L).build(), Trip.builder().id(41L).build()));

        VehicleResponse response = service.updateVehicle(5L, new UpdateVehicleRequest(" Xe 01A ", "51c-123.45", 4L));

        assertEquals("Xe 01A", response.name());
        assertEquals("51C-123.45", response.licensePlate());
        assertEquals(4L, response.vehicleTypeId());
        verify(loadPlanRepository).markStaleByTripId(40L);
        verify(loadPlanRepository).markStaleByTripId(41L);
    }

    @Test
    void renamingKeepsPlansFresh() {
        service.updateVehicle(5L, new UpdateVehicleRequest("Xe mới", "51C-123.45", 3L));

        verify(loadPlanRepository, never()).markStaleByTripId(anyLong());
    }

    @Test
    void typeCannotChangeWhileTheVehicleRuns() {
        when(tripRepository.existsByVehicleIdAndStatusIn(5L, RUNNING)).thenReturn(true);

        AppException error = assertThrows(AppException.class,
                () -> service.updateVehicle(5L, new UpdateVehicleRequest("Xe 01", "51C-123.45", 4L)));

        assertEquals(ErrorCode.VEHICLE_BUSY, error.getErrorCode());
    }

    @Test
    void plateOfAnotherVehicleIsRejected() {
        Vehicle other = Vehicle.builder().id(6L).licensePlate("51C-999.99").build();
        when(vehicleRepository.findByLicensePlateIgnoreCase("51C-999.99")).thenReturn(Optional.of(other));

        AppException error = assertThrows(AppException.class,
                () -> service.updateVehicle(5L, new UpdateVehicleRequest("Xe 01", "51c-999.99", 3L)));

        assertEquals(ErrorCode.LICENSE_PLATE_ALREADY_EXISTS, error.getErrorCode());
    }

    @Test
    void retiredVehicleIsReadOnly() {
        vehicle.setStatus(VehicleStatus.RETIRED);

        assertEquals(ErrorCode.VEHICLE_RETIRED, assertThrows(AppException.class,
                () -> service.updateVehicle(5L, new UpdateVehicleRequest("Xe 01", "51C-123.45", 3L))).getErrorCode());
        assertEquals(ErrorCode.VEHICLE_RETIRED,
                assertThrows(AppException.class, () -> service.startMaintenance(5L, "Thay lốp")).getErrorCode());
    }

    @Test
    void vehicleOfAnUnfinishedTripCannotBeDeleted() {
        when(tripRepository.existsByVehicleIdAndStatusIn(eq(5L), any())).thenReturn(true);

        AppException error = assertThrows(AppException.class, () -> service.deleteVehicle(5L));

        assertEquals(ErrorCode.VEHICLE_IN_USE, error.getErrorCode());
        verify(vehicleRepository, never()).delete(any(Vehicle.class));
    }

    @Test
    void vehicleWithFinishedTripsIsRetiredInsteadOfDeleted() {
        when(tripRepository.existsByVehicleId(5L)).thenReturn(true);

        VehicleDeleteResponse response = service.deleteVehicle(5L);

        assertEquals(VehicleDeleteResponse.RETIRED, response.outcome());
        assertEquals(VehicleStatus.RETIRED, vehicle.getStatus());
        verify(vehicleRepository, never()).delete(any(Vehicle.class));
    }

    @Test
    void unusedVehicleIsDeleted() {
        VehicleDeleteResponse response = service.deleteVehicle(5L);

        assertEquals(VehicleDeleteResponse.DELETED, response.outcome());
        verify(vehicleRepository).delete(vehicle);
    }

    @Test
    void busyVehicleCannotGoToMaintenance() {
        when(tripRepository.existsByVehicleIdAndStatusIn(5L, RUNNING)).thenReturn(true);

        AppException error = assertThrows(AppException.class, () -> service.startMaintenance(5L, "Thay lốp"));

        assertEquals(ErrorCode.VEHICLE_BUSY, error.getErrorCode());
        assertEquals(VehicleStatus.AVAILABLE, vehicle.getStatus());
    }

    @Test
    void maintenanceKeepsItsStartWhenTheNoteChanges() {
        VehicleResponse started = service.startMaintenance(5L, "  Thay lốp  ");
        assertEquals(VehicleStatus.MAINTENANCE, started.status());
        assertEquals("Thay lốp", started.maintenanceNote());
        assertNotNull(started.maintenanceSince());

        LocalDateTime since = LocalDateTime.of(2026, 11, 1, 8, 0);
        vehicle.setMaintenanceSince(since);
        VehicleResponse edited = service.startMaintenance(5L, "Thay lốp và má phanh");

        assertEquals(since, edited.maintenanceSince());
        assertEquals("Thay lốp và má phanh", edited.maintenanceNote());
    }

    @Test
    void endingMaintenanceClearsItAndIsIdempotent() {
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        vehicle.setMaintenanceNote("Thay lốp");
        vehicle.setMaintenanceSince(LocalDateTime.of(2026, 11, 1, 8, 0));

        VehicleResponse ended = service.endMaintenance(5L);
        assertEquals(VehicleStatus.AVAILABLE, ended.status());
        assertNull(ended.maintenanceNote());
        assertNull(vehicle.getMaintenanceSince());

        assertEquals(VehicleStatus.AVAILABLE, service.endMaintenance(5L).status());
    }

    @Test
    void responseNamesTheTripTheVehicleIsServing() {
        when(tripRepository.findByVehicleIdAndStatusIn(5L, RUNNING)).thenReturn(
                List.of(Trip.builder().id(40L).tripCode("TRIP-2026-0001").status(TripStatus.IN_TRANSIT).build()));

        VehicleResponse response = service.endMaintenance(5L);

        assertEquals(VehicleStatus.IN_TRIP, response.status());
        assertEquals("TRIP-2026-0001", response.currentTripCode());
        assertEquals(TripStatus.IN_TRANSIT, response.currentTripStatus());
    }
}
