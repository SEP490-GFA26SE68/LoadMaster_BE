package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverServiceImpl;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverServiceImplTest {

    @Mock private TripRepository tripRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private UnloadConfirmationRepository unloadConfirmationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private DeliveryRequirementRepository deliveryRequirementRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private TripStatusService tripStatusService;
    @Mock private TripPackageResolver tripPackageResolver;
    @Mock private TripLookupHelper tripLookupHelper;

    @InjectMocks
    private DriverServiceImpl service;

    private User driver;
    private Trip trip;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        driver = User.builder().id(8L).company(company).build();
        trip = Trip.builder().id(10L).company(company).driver(driver).status(TripStatus.LOADING).build();
        when(currentUserService.getCurrentUser()).thenReturn(driver);
        lenient().when(tripLookupHelper.requireTrip("10", 1L)).thenReturn(trip);
    }

    @Test
    void startRequiresCompletedLoading() {
        when(loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(10L, LoadingExecutionStatus.COMPLETED))
                .thenReturn(false);

        AppException error = assertThrows(AppException.class, () -> service.startTrip(10L));

        assertEquals(ErrorCode.LOADING_NOT_COMPLETED, error.getErrorCode());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void startMovesRequirementsIntoTripAndTripInTransit() {
        DeliveryRequirement requirement = DeliveryRequirement.builder()
                .id(20L).status(DeliveryRequirementStatus.ASSIGNED).build();
        when(loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(10L, LoadingExecutionStatus.COMPLETED))
                .thenReturn(true);
        when(deliveryRequirementRepository.findByTripId(10L)).thenReturn(List.of(requirement));
        when(tripStatusService.transitionTo(10L, TripStatus.IN_TRANSIT, 8L)).thenAnswer(invocation -> {
            trip.setStatus(TripStatus.IN_TRANSIT);
            return trip;
        });

        assertEquals("IN_TRANSIT", service.startTrip(10L).getStatus());
        assertEquals(DeliveryRequirementStatus.IN_TRIP, requirement.getStatus());
    }

    @Test
    void anotherDriversTripIsRejected() {
        trip.setDriver(User.builder().id(99L).build());

        AppException error = assertThrows(AppException.class, () -> service.startTrip(10L));

        assertEquals(ErrorCode.TRIP_NOT_ASSIGNED_TO_DRIVER, error.getErrorCode());
    }

    @Test
    void driverIdOfSomeoneElseIsRejected() {
        AppException error = assertThrows(AppException.class, () -> service.listTrips(99L));

        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
    }

    @Test
    void unloadFindsStopThroughDeliveryRequirement() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).build();
        CargoPackage pkg = CargoPackage.builder().id(1L).trackingBarcode("PK-1").status(PackageStatus.LOADED).build();
        when(cargoPackageRepository.findById(1L)).thenReturn(Optional.of(pkg));
        when(tripPackageResolver.stopOfPackage(pkg)).thenReturn(Optional.of(stop));
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(stop));
        when(unloadConfirmationRepository.save(any())).thenAnswer(invocation -> {
            UnloadConfirmation saved = invocation.getArgument(0);
            saved.setId(40L);
            return saved;
        });
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(pkg));
        when(unloadConfirmationRepository.countByDeliveryStopId(30L)).thenReturn(1L);

        ConfirmUnloadResponse response = service.confirmUnload(1L, null);

        assertEquals(30L, response.getStopId());
        assertEquals(0, response.getRemainingPackagesCount());
        assertEquals(PackageStatus.DELIVERED, pkg.getStatus());
    }

    @Test
    void completingLastStopDeliversTripThroughStatusService() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).build();
        CargoPackage pkg = CargoPackage.builder().id(1L).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(pkg));
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(true);
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.empty());
        when(tripStatusService.transitionTo(10L, TripStatus.DELIVERED, 8L)).thenAnswer(invocation -> {
            trip.setStatus(TripStatus.DELIVERED);
            return trip;
        });

        CompleteStopResponse response = service.completeStop(30L, 8L);

        assertTrue(response.getTripCompleted());
        assertEquals("DELIVERED", response.getTripStatus());
        assertEquals(DeliveryStopStatus.COMPLETED, stop.getStatus());
    }
}
