package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.driver.ArriveStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.constant.driver.DeliveryIssueKind;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.entity.driver.DeliveryIssue;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @Mock private DeliveryIssueRepository deliveryIssueRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;

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

    @Test
    void completingStopLeavesNextStopPendingUntilDriverArrives() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).actualArrival(LocalDateTime.now()).build();
        DeliveryStop next = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2)
                .status(DeliveryStopStatus.PENDING).build();
        CargoPackage pkg = CargoPackage.builder().id(1L).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(pkg));
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(true);
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(next));

        CompleteStopResponse response = service.completeStop(30L, 8L);

        assertEquals(31L, response.getNextStopId());
        assertEquals(DeliveryStopStatus.PENDING, next.getStatus());
        assertNull(next.getActualArrival());
    }

    @Test
    void listTripsFlagsLoadingCompletedTrips() {
        Trip waiting = Trip.builder().id(11L).company(trip.getCompany()).driver(driver).status(TripStatus.LOADING).build();
        when(tripRepository.findByDriver_IdOrderByDepartureTimeDesc(8L)).thenReturn(List.of(trip, waiting));
        when(loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(10L, LoadingExecutionStatus.COMPLETED))
                .thenReturn(true);
        when(loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(11L, LoadingExecutionStatus.COMPLETED))
                .thenReturn(false);

        List<DriverTripResponse> trips = service.listTrips(null);

        assertEquals(2, trips.size());
        assertEquals("LOADING", trips.get(0).getStatus());
        assertTrue(trips.get(0).getLoadingCompleted());
        assertFalse(trips.get(1).getLoadingCompleted());
    }

    @Test
    void arrivingRecordsActualArrivalAndAuditLog() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).stopName("Biên Hoà").build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(stop));

        ArriveStopResponse response = service.arriveAtStop(30L);

        assertEquals("ARRIVED", response.getStatus());
        assertNotNull(response.getActualArrival());
        assertEquals(DeliveryStopStatus.ARRIVED, stop.getStatus());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("STOP_ARRIVED", audit.getValue().getActionType());
        assertEquals(1L, audit.getValue().getCompanyId());
    }

    @Test
    void currentStopCarriesArrivalCodesAndUnloadState() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        LocalDateTime arrivedAt = LocalDateTime.of(2026, 11, 6, 9, 30);
        LocalDateTime unloadedAt = LocalDateTime.of(2026, 11, 6, 9, 40);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).actualArrival(arrivedAt).build();
        CargoPackage done = CargoPackage.builder().id(1L).packageCode("SHIP-1").qrToken("qr-1")
                .trackingBarcode("PK-1").status(PackageStatus.DELIVERED).build();
        CargoPackage waiting = CargoPackage.builder().id(2L).packageCode("SHIP-2").qrToken("qr-2")
                .trackingBarcode("PK-2").status(PackageStatus.LOADED).build();
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(stop));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(done, waiting));
        when(unloadConfirmationRepository.findByDeliveryStopId(30L)).thenReturn(List.of(
                UnloadConfirmation.builder().id(40L).cargoPackage(done).deliveryStop(stop).unloadedAt(unloadedAt).build()));

        var response = service.getCurrentStop(10L);

        assertEquals(arrivedAt, response.getActualArrival());
        assertEquals(2, response.getPackages().size());
        var first = response.getPackages().get(0);
        assertEquals("qr-1", first.getQrToken());
        assertEquals("SHIP-1", first.getPackageCode());
        assertEquals("PK-1", first.getTrackingBarcode());
        assertTrue(first.getUnloaded());
        assertEquals(unloadedAt, first.getUnloadedAt());
        var second = response.getPackages().get(1);
        assertFalse(second.getUnloaded());
        assertEquals(null, second.getUnloadedAt());
    }

    @Test
    void arrivingTwiceIsRejected() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .actualArrival(LocalDateTime.now()).status(DeliveryStopStatus.ARRIVED).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));

        AppException error = assertThrows(AppException.class, () -> service.arriveAtStop(30L));

        assertEquals(ErrorCode.DELIVERY_STOP_ALREADY_ARRIVED, error.getErrorCode());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void arrivingAtLaterStopIsRejected() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop current = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).build();
        DeliveryStop later = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2).build();
        when(deliveryStopRepository.findById(31L)).thenReturn(Optional.of(later));
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(current));

        AppException error = assertThrows(AppException.class, () -> service.arriveAtStop(31L));

        assertEquals(ErrorCode.DELIVERY_STOP_NOT_CURRENT, error.getErrorCode());
    }

    @Test
    void arrivingAtCompletedStopOrBeforeDepartureIsRejected() {
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.COMPLETED).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));

        AppException notInTransit = assertThrows(AppException.class, () -> service.arriveAtStop(30L));
        assertEquals(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT, notInTransit.getErrorCode());

        trip.setStatus(TripStatus.IN_TRANSIT);
        AppException completed = assertThrows(AppException.class, () -> service.arriveAtStop(30L));
        assertEquals(ErrorCode.DELIVERY_STOP_ALREADY_COMPLETED, completed.getErrorCode());
    }

    @Test
    void completingStopWithPendingManualConfirmIsRejected() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(manualConfirmationRepository.countByDeliveryStopIdAndStatus(30L, ManualConfirmStatus.PENDING)).thenReturn(1L);

        AppException error = assertThrows(AppException.class, () -> service.completeStop(30L, null));

        assertEquals(ErrorCode.MANUAL_CONFIRM_PENDING, error.getErrorCode());
        assertEquals(DeliveryStopStatus.ARRIVED, stop.getStatus());
    }

    @Test
    void completingStopReturnsPackagesWithAnIssue() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).build();
        DeliveryStop next = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2)
                .status(DeliveryStopStatus.PENDING).build();
        CargoPackage delivered = CargoPackage.builder().id(1L).build();
        CargoPackage refused = CargoPackage.builder().id(2L).status(PackageStatus.LOADED).build();
        DeliveryIssue issue = DeliveryIssue.builder().id(50L).cargoPackage(refused).deliveryStop(stop)
                .kind(DeliveryIssueKind.REFUSED).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(deliveryIssueRepository.findByDeliveryStopIdOrderByReportedAtAsc(30L)).thenReturn(List.of(issue));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(delivered, refused));
        when(unloadConfirmationRepository.existsByCargoPackageId(1L)).thenReturn(true);
        when(unloadConfirmationRepository.existsByCargoPackageId(2L)).thenReturn(false);
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(next));

        CompleteStopResponse response = service.completeStop(30L, null);

        assertEquals(1, response.getDeliveredCount());
        assertEquals(1, response.getReturnedCount());
        assertEquals(Boolean.TRUE, issue.getReturned());
        assertEquals(DeliveryStopStatus.COMPLETED, stop.getStatus());
        assertEquals(31L, response.getNextStopId());
    }

    @Test
    void completingStopStillNeedsPackagesWithoutAnIssue() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).build();
        CargoPackage waiting = CargoPackage.builder().id(2L).build();
        when(deliveryStopRepository.findById(30L)).thenReturn(Optional.of(stop));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(waiting));
        when(unloadConfirmationRepository.existsByCargoPackageId(2L)).thenReturn(false);

        assertThrows(fu.se184491.loadmaster_be.exception.IncompleteStopException.class, () -> service.completeStop(30L, null));
    }

    @Test
    void currentStopCarriesIssueAndManualConfirmState() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        DeliveryStop stop = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1)
                .status(DeliveryStopStatus.ARRIVED).build();
        CargoPackage refused = CargoPackage.builder().id(1L).status(PackageStatus.LOADED).build();
        CargoPackage rejected = CargoPackage.builder().id(2L).status(PackageStatus.LOADED).build();
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(stop));
        when(tripPackageResolver.packagesOfStop(30L)).thenReturn(List.of(refused, rejected));
        when(deliveryIssueRepository.findByDeliveryStopIdOrderByReportedAtAsc(30L)).thenReturn(List.of(
                DeliveryIssue.builder().id(50L).cargoPackage(refused).kind(DeliveryIssueKind.REFUSED).note("Khách không nhận").build()));
        when(manualConfirmationRepository.findByDeliveryStopIdOrderByRequestedAtAsc(30L)).thenReturn(List.of(
                ManualUnloadConfirmation.builder().id(60L).cargoPackage(rejected)
                        .status(ManualConfirmStatus.REJECTED).decisionNote("Quét lại nhãn").build()));

        var response = service.getCurrentStop(10L);

        assertEquals("REFUSED", response.getPackages().get(0).getIssueKind());
        assertEquals("Khách không nhận", response.getPackages().get(0).getIssueNote());
        assertEquals("REJECTED", response.getPackages().get(1).getManualStatus());
        assertEquals("Quét lại nhãn", response.getPackages().get(1).getManualRejectReason());
    }
}
