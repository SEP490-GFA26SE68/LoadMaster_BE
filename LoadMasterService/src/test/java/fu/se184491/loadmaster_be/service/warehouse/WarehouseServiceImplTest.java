package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.warehouse.CompleteLoadingResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.dto.response.warehouse.PlacementStepResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import fu.se184491.loadmaster_be.service.warehouse.Impl.WarehouseServiceImpl;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceImplTest {

    @Mock private TripRepository tripRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlacementConfirmationRepository placementConfirmationRepository;
    @Mock private DeviationRepository deviationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private TripStatusService tripStatusService;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private TripPackageResolver tripPackageResolver;

    @InjectMocks
    private WarehouseServiceImpl service;

    private Trip trip;
    private LoadPlan plan;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        User worker = User.builder().id(9L).company(company).build();
        trip = Trip.builder().id(10L).company(company).tripCode("TRIP-10").status(TripStatus.PLANNED).build();
        plan = LoadPlan.builder().id(50L).approved(true).build();
        when(currentUserService.getCurrentUser()).thenReturn(worker);
        when(tripRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(trip));
    }

    @Test
    void startMovesPlannedTripToLoading() {
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.empty());
        when(loadingExecutionRepository.save(any())).thenAnswer(invocation -> {
            LoadingExecution saved = invocation.getArgument(0);
            saved.setId(60L);
            return saved;
        });
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of());

        service.startLoading(10L, null);

        verify(tripStatusService).transitionTo(10L, TripStatus.LOADING, 9L);
    }

    @Test
    void draftTripCannotStartLoading() {
        trip.setStatus(TripStatus.DRAFT);

        AppException error = assertThrows(AppException.class, () -> service.startLoading(10L, null));

        assertEquals(ErrorCode.TRIP_NOT_READY_FOR_LOADING, error.getErrorCode());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void completingLoadingMarksPackagesLoadedAndWaitsForDriver() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage pkg = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan).cargoPackage(pkg).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanId(50L)).thenReturn(List.of(placement));
        when(placementConfirmationRepository.existsByPlacementId(70L)).thenReturn(true);

        CompleteLoadingResponse response = service.completeLoading(10L);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals("LOADING", response.getTripStatus());
        assertEquals(PackageStatus.LOADED, pkg.getStatus());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void reopeningSessionReturnsStatusStopAndCargoSpace() {
        trip.setStatus(TripStatus.LOADING);
        trip.setVehicle(Vehicle.builder().id(3L).licensePlate("51C-123.45")
                .vehicleType(VehicleType.builder().name("Xe 8 tấn")
                        .innerLength(720).innerWidth(235).innerHeight(240)
                        .doorWidth(230).doorHeight(230).build())
                .build());
        CargoPackage first = CargoPackage.builder().id(1L).packageCode("DH-001-001")
                .trackingBarcode("DH-001-001").qrToken("token-1").build();
        CargoPackage second = CargoPackage.builder().id(2L).packageCode("DH-001-002").qrToken("token-2").build();
        CargoPackage third = CargoPackage.builder().id(3L).packageCode("DH-001-003").qrToken("token-3").build();
        PackagePlacement p1 = PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(first).stepSequence(1).build();
        PackagePlacement p2 = PackagePlacement.builder().id(72L).loadPlan(plan).cargoPackage(second).stepSequence(2).build();
        PackagePlacement p3 = PackagePlacement.builder().id(73L).loadPlan(plan).cargoPackage(third).stepSequence(3).build();
        DeliveryStop stopOne = DeliveryStop.builder().id(201L).stopSequence(1).stopName("Biên Hoà").build();
        DeliveryStop stopTwo = DeliveryStop.builder().id(202L).stopSequence(2).stopName("Thủ Đức").build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();

        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(p1, p2, p3));
        when(placementConfirmationRepository.findByPlacementLoadPlanId(50L))
                .thenReturn(List.of(PlacementConfirmation.builder().placement(p1).build()));
        when(deviationRepository.findByPlacementLoadPlanId(50L))
                .thenReturn(List.of(Deviation.builder().placement(p2).build()));
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(stopOne, stopTwo));
        when(tripPackageResolver.packagesOfStop(201L)).thenReturn(List.of(first));
        when(tripPackageResolver.packagesOfStop(202L)).thenReturn(List.of(second, third));

        StartLoadingResponse session = service.getSession(10L);

        assertEquals(60L, session.getExecutionId());
        assertEquals("IN_PROGRESS", session.getStatus());
        assertEquals(720, session.getInnerLength());
        assertEquals(235, session.getInnerWidth());
        assertEquals(240, session.getInnerHeight());
        assertEquals(230, session.getDoorWidth());
        assertEquals(230, session.getDoorHeight());
        assertEquals("51C-123.45", session.getLicensePlate());
        assertEquals(3, session.getTotalPackages());
        assertEquals(1, session.getConfirmedCount());
        assertEquals(1, session.getDeviationCount());
        assertEquals(1, session.getPendingCount());
        List<PlacementStepResponse> steps = session.getPlacements();
        assertEquals(List.of("CONFIRMED", "DEVIATION", "PENDING"),
                steps.stream().map(PlacementStepResponse::getStatus).toList());
        assertEquals("token-1", steps.get(0).getQrToken());
        assertEquals("DH-001-001", steps.get(0).getPackageCode());
        assertEquals("DH-001-001", steps.get(0).getTrackingBarcode());
        assertEquals(1, steps.get(0).getStopSequence());
        assertEquals(202L, steps.get(2).getStopId());
        assertEquals(2, steps.get(2).getStopSequence());
        assertEquals("Thủ Đức", steps.get(2).getStopName());
    }

    @Test
    void reopeningSessionOfTripNotLoadingIsRejected() {
        AppException error = assertThrows(AppException.class, () -> service.getSession(10L));

        assertEquals(ErrorCode.TRIP_NOT_LOADING, error.getErrorCode());
    }

    @Test
    void reopeningSessionWithoutExecutionIsNotFound() {
        trip.setStatus(TripStatus.LOADING);
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class, () -> service.getSession(10L));

        assertEquals(ErrorCode.LOADING_EXECUTION_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void completingWithUnhandledPlacementThrowsIncompleteLoading() {
        trip.setStatus(TripStatus.LOADING);
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanId(50L)).thenReturn(List.of(placement));

        assertThrows(fu.se184491.loadmaster_be.exception.IncompleteLoadingException.class,
                () -> service.completeLoading(10L));
    }
}
