package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.trip.TripUpdateRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import java.math.BigDecimal;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripLifecycleServiceTest {

    @Mock private TripRepository tripRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private DeliveryRequirementRepository deliveryRequirementRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private UserRepository userRepository;
    @Mock private CompanyWarehouseRepository companyWarehouseRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private TripStatusService tripStatusService;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private TripPackageResolver tripPackageResolver;

    @InjectMocks
    private TripLifecycleService service;

    private Trip trip;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        User dispatcher = User.builder().id(7L).company(company).build();
        trip = Trip.builder()
                .id(10L)
                .company(company)
                .vehicle(Vehicle.builder().id(3L).build())
                .tripName("Chuyến sáng")
                .departureTime(LocalDateTime.of(2026, 11, 6, 8, 0))
                .status(TripStatus.PLANNED)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        when(tripRepository.findByIdAndCompanyIdForUpdate(10L, 1L)).thenReturn(Optional.of(trip));
    }

    @Test
    void cancelReleasesRequirementsPackagesAndAbortsLoading() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage loaded = CargoPackage.builder().id(1L).status(PackageStatus.LOADED).build();
        CargoPackage planned = CargoPackage.builder().id(2L).status(PackageStatus.PLANNED).build();
        DeliveryRequirement requirement = requirement(DeliveryRequirementStatus.ASSIGNED, loaded, planned);
        LoadingExecution execution = LoadingExecution.builder()
                .id(5L).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(deliveryRequirementRepository.findByTripId(10L)).thenReturn(List.of(requirement));
        when(loadingExecutionRepository.findAllByLoadPlanJobTripId(10L)).thenReturn(List.of(execution));

        service.cancelTrip(10L, "Khách huỷ đơn");

        assertEquals(PackageStatus.PENDING, loaded.getStatus());
        assertEquals(PackageStatus.PENDING, planned.getStatus());
        assertEquals(DeliveryRequirementStatus.PENDING, requirement.getStatus());
        assertNull(requirement.getTrip());
        assertEquals(LoadingExecutionStatus.ABORTED, execution.getStatus());
        verify(tripStatusService).transitionTo(10L, TripStatus.CANCELLED, 7L);
    }

    @Test
    void tripInTransitCannotBeCancelled() {
        trip.setStatus(TripStatus.IN_TRANSIT);

        AppException error = assertThrows(AppException.class, () -> service.cancelTrip(10L, "Hỏng xe"));

        assertEquals(ErrorCode.TRIP_NOT_CANCELLABLE, error.getErrorCode());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void removingRequirementDeletesItsStopAndSendsTripBackToDraft() {
        CargoPackage planned = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        DeliveryRequirement requirement = requirement(DeliveryRequirementStatus.ASSIGNED, planned);
        DeliveryStop removed = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).build();
        DeliveryStop kept = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2).build();
        when(deliveryRequirementRepository.findByIdAndCompanyIdForUpdate(20L, 1L))
                .thenReturn(Optional.of(requirement));
        when(deliveryStopRepository.findByTripIdAndDeliveryRequirementId(10L, 20L)).thenReturn(List.of(removed));
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(kept));

        service.removeRequirementFromTrip(10L, 20L);

        verify(deliveryStopRepository).deleteRouteLegsTouchingStop(30L);
        verify(deliveryStopRepository).detachPlacementsFromStop(30L);
        verify(deliveryStopRepository).delete(removed);
        assertEquals(1, kept.getStopSequence());
        assertEquals(PackageStatus.PENDING, planned.getStatus());
        assertEquals(DeliveryRequirementStatus.PENDING, requirement.getStatus());
        assertNull(requirement.getTrip());
        verify(tripStatusService).invalidateRoute(trip, 7L);
        verify(loadPlanRepository).markStaleByTripId(10L);
    }

    @Test
    void requirementOfAnotherTripCannotBeRemoved() {
        DeliveryRequirement requirement = requirement(DeliveryRequirementStatus.ASSIGNED);
        requirement.setTrip(Trip.builder().id(99L).build());
        when(deliveryRequirementRepository.findByIdAndCompanyIdForUpdate(20L, 1L))
                .thenReturn(Optional.of(requirement));

        AppException error = assertThrows(AppException.class,
                () -> service.removeRequirementFromTrip(10L, 20L));

        assertEquals(ErrorCode.DELIVERY_REQUIREMENT_NOT_IN_TRIP, error.getErrorCode());
        verify(deliveryStopRepository, never()).delete(any());
    }

    @Test
    void changingDepartureTimeInvalidatesRoute() {
        LocalDateTime later = LocalDateTime.of(2026, 11, 6, 13, 0);

        service.updateTrip(10L, new TripUpdateRequest(null, later, null, null, null, null));

        assertEquals(later, trip.getDepartureTime());
        verify(tripStatusService).invalidateRoute(trip, 7L);
    }

    @Test
    void renamingKeepsRoute() {
        service.updateTrip(10L, new TripUpdateRequest("Chuyến chiều", null, null, null, null, null));

        assertEquals("Chuyến chiều", trip.getTripName());
        verify(tripStatusService, never()).invalidateRoute(any(), any());
    }

    @Test
    void changingVehicleMarksPlansStaleButKeepsRoute() {
        Vehicle bigger = vehicle(4L, 1000, 600, 240, 240);
        when(vehicleRepository.findById(4L)).thenReturn(Optional.of(bigger));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(box("PK-1", 120, 80, 60, 300)));

        service.updateTrip(10L, new TripUpdateRequest(null, null, 4L, null, null, null));

        assertEquals(bigger, trip.getVehicle());
        verify(loadPlanRepository).markStaleByTripId(10L);
        verify(tripStatusService, never()).invalidateRoute(any(), any());
    }

    @Test
    void vehicleTooWeakForCargoIsRejected() {
        when(vehicleRepository.findById(4L)).thenReturn(Optional.of(vehicle(4L, 500, 600, 240, 240)));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(box("PK-1", 120, 80, 60, 300), box("PK-2", 120, 80, 60, 300)));

        AppException error = assertThrows(AppException.class,
                () -> service.updateTrip(10L, new TripUpdateRequest(null, null, 4L, null, null, null)));

        assertEquals(ErrorCode.VEHICLE_CANNOT_CARRY_CARGO, error.getErrorCode());
        verify(loadPlanRepository, never()).markStaleByTripId(any());
    }

    @Test
    void packageThatFitsNoOrientationIsRejected() {
        when(vehicleRepository.findById(4L)).thenReturn(Optional.of(vehicle(4L, 5000, 400, 200, 200)));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(box("PK-LONG", 450, 50, 50, 20)));

        AppException error = assertThrows(AppException.class,
                () -> service.updateTrip(10L, new TripUpdateRequest(null, null, 4L, null, null, null)));

        assertEquals(ErrorCode.VEHICLE_CANNOT_CARRY_CARGO, error.getErrorCode());
    }

    private Vehicle vehicle(Long id, int payloadKg, int length, int width, int height) {
        VehicleType type = VehicleType.builder()
                .maxPayloadKg(BigDecimal.valueOf(payloadKg))
                .innerLength(length).innerWidth(width).innerHeight(height)
                .build();
        return Vehicle.builder().id(id).company(trip.getCompany()).status(VehicleStatus.AVAILABLE).vehicleType(type).build();
    }

    private static CargoPackage box(String barcode, double length, double width, double height, double kg) {
        return CargoPackage.builder().trackingBarcode(barcode)
                .actualLength(BigDecimal.valueOf(length)).actualWidth(BigDecimal.valueOf(width))
                .actualHeight(BigDecimal.valueOf(height)).actualWeightKg(BigDecimal.valueOf(kg))
                .build();
    }

    @Test
    void loadingTripCannotBeEdited() {
        trip.setStatus(TripStatus.LOADING);

        AppException error = assertThrows(AppException.class,
                () -> service.updateTrip(10L, new TripUpdateRequest("X", null, null, null, null, null)));

        assertEquals(ErrorCode.TRIP_NOT_EDITABLE, error.getErrorCode());
    }

    private DeliveryRequirement requirement(DeliveryRequirementStatus status, CargoPackage... packages) {
        return DeliveryRequirement.builder()
                .id(20L)
                .status(status)
                .trip(trip)
                .packages(new LinkedHashSet<>(List.of(packages)))
                .build();
    }
}
