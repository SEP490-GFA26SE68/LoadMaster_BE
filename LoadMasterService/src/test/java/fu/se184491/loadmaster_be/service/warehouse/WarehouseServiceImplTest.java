package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.ExecutionAbortReason;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
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
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.dto.request.warehouse.StartLoadingRequest;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceImplTest {

    @Mock private TripRepository tripRepository;
    @Mock private fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility vehicleEligibility;
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
    @Mock private WarehouseProgress warehouseProgress;
    @Mock private LoadingDamageRepository loadingDamageRepository;
    @Mock private fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore presenceStore;

    @Mock private jakarta.persistence.EntityManager entityManager;
    @InjectMocks
    private WarehouseServiceImpl service;

    private Trip trip;
    private LoadPlan plan;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        User worker = User.builder().id(9L).company(company).build();
        trip = Trip.builder().vehicle(fu.se184491.loadmaster_be.entity.vehicle.Vehicle.builder().id(3L).build()).id(10L).company(company).tripCode("TRIP-10").status(TripStatus.PLANNED).build();
        plan = LoadPlan.builder().id(50L).approved(true).job(OptimizationJob.builder().trip(trip).build()).build();
        var lookup = new WarehouseSessionLookup(currentUserService, tripRepository, loadPlanRepository,
                loadingExecutionRepository, packagePlacementRepository, placementConfirmationRepository,
                deviationRepository, tripPackageResolver, warehouseProgress, auditLogRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(lookup, "entityManager", entityManager);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "lookup", lookup);
        lenient().when(tripRepository.findByIdAndCompanyIdForUpdate(10L, 1L)).thenReturn(Optional.of(trip));
        when(currentUserService.getCurrentUser()).thenReturn(worker);
        lenient().when(tripRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(trip));
        lenient().when(warehouseProgress.of(any(), any())).thenReturn(WarehouseProgress.State.empty());
    }

    @Test
    void staleApprovedPreviewIsRejectedWithoutStartingOrChangingExecution() {
        plan.setStale(true);
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        assertEquals(ErrorCode.PLAN_STALE, assertThrows(AppException.class, () -> service.getApprovedPlan(10L)).getErrorCode());
        verify(loadingExecutionRepository, never()).save(any());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void approvedPlanPreviewBeforeLoadingDoesNotStartOrPersistExecution() {
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        StartLoadingResponse preview = service.getApprovedPlan(10L);
        assertEquals(50L, preview.getLoadPlanId());
        assertEquals(Boolean.TRUE, preview.getApproved());
        assertEquals(plan.getStale(), preview.getStale());
        assertEquals("NOT_STARTED", preview.getStatus());
        assertNull(preview.getExecutionId());
        assertFalse(preview.getStagingComplete());
        assertEquals(TripStatus.PLANNED, trip.getStatus());
        verify(loadingExecutionRepository, never()).save(any());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void approvedPlanPreviewRejectsAnotherCompanyBeforeReadingGeometry() {
        when(tripRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.empty());
        assertThrows(AppException.class, () -> service.getApprovedPlan(10L));
        verify(loadPlanRepository, never()).findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(any());
    }

    @Test
    void approvedPlanPreviewRejectsMissingApproval() {
        assertThrows(AppException.class, () -> service.getApprovedPlan(10L));
        verify(packagePlacementRepository, never()).findByLoadPlanIdOrderByStepSequenceAsc(any());
    }

    @Test
    void approvedPlanPreviewKeepsCompletedExecutionReadOnly() {
        trip.setStatus(TripStatus.LOADING);
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(
                LoadingExecution.builder().id(60L).status(LoadingExecutionStatus.COMPLETED).build()));
        StartLoadingResponse preview = service.getApprovedPlan(10L);
        assertEquals("COMPLETED", preview.getStatus());
        assertEquals(60L, preview.getExecutionId());
        verify(loadingExecutionRepository, never()).save(any());
    }

    @Test
    void approvedPlanPreviewRejectsAnAbortedExecution() {
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(
                LoadingExecution.builder().id(60L).status(LoadingExecutionStatus.ABORTED).build()));
        assertThrows(AppException.class, () -> service.getApprovedPlan(10L));
        verify(packagePlacementRepository, never()).findByLoadPlanIdOrderByStepSequenceAsc(any());
    }

    @Test
    void startWithStagingRecordsTheStagingStep() {
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.empty());
        when(loadingExecutionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of());
        StartLoadingRequest request = new StartLoadingRequest();
        request.setStaging(true);

        StartLoadingResponse session = service.startLoading(10L, request);

        assertTrue(session.getStagingRequired());
        assertTrue(session.getStagingComplete());
    }

    @Test
    void startingAgainOnAnAbortedSessionOfTheApprovedPlanReopensItFromScratch() {
        LoadingExecution aborted = LoadingExecution.builder().id(60L).loadPlan(plan).status(LoadingExecutionStatus.ABORTED)
                .abortReason(ExecutionAbortReason.DAMAGED_PACKAGE).sealNumber("S-1").totalDeviations(2).build();
        PlacementConfirmation oldConfirm = PlacementConfirmation.builder().id(80L).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(aborted));
        when(loadingExecutionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of());
        when(placementConfirmationRepository.findByPlacementLoadPlanId(50L)).thenReturn(List.of(oldConfirm));

        StartLoadingResponse session = service.startLoading(10L, null);

        assertEquals(60L, session.getExecutionId());
        assertEquals(LoadingExecutionStatus.IN_PROGRESS, aborted.getStatus());
        assertNull(aborted.getAbortReason());
        assertNull(aborted.getSealNumber());
        assertEquals(0, aborted.getTotalDeviations());
        verify(placementConfirmationRepository).deleteAll(List.of(oldConfirm));
        verify(tripStatusService).transitionTo(10L, TripStatus.LOADING, 9L);
    }

    @Test
    void startingWhileTheApprovedPlanStillHasALiveSessionIsRejected() {
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.COMPLETED).build()));

        AppException error = assertThrows(AppException.class, () -> service.startLoading(10L, null));

        assertEquals(ErrorCode.TRIP_ALREADY_LOADING, error.getErrorCode());
    }

    @Test
    void tripsWaitingForANewPlanAfterADropAreListedOnlyOnRequest() {
        Trip draft = Trip.builder().id(11L).company(trip.getCompany()).tripCode("TRIP-11").status(TripStatus.DRAFT).build();
        LoadPlan stalePlan = LoadPlan.builder().id(40L).build();
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.PLANNED)).thenReturn(List.of(trip));
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.LOADING)).thenReturn(List.of());
        lenient().when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.DRAFT)).thenReturn(List.of(draft));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.empty());
        lenient().when(loadingExecutionRepository.findAllByLoadPlanJobTripId(10L)).thenReturn(List.of(
                LoadingExecution.builder().id(58L).loadPlan(stalePlan).status(LoadingExecutionStatus.ABORTED).build(),
                LoadingExecution.builder().id(60L).loadPlan(stalePlan).status(LoadingExecutionStatus.ABORTED)
                        .abortReason(ExecutionAbortReason.STAGING_SHORTAGE).build()));
        lenient().when(loadingExecutionRepository.findAllByLoadPlanJobTripId(11L)).thenReturn(List.of(
                LoadingExecution.builder().id(61L).loadPlan(stalePlan).status(LoadingExecutionStatus.ABORTED)
                        .abortReason(ExecutionAbortReason.DAMAGED_PACKAGE).build()));

        assertTrue(service.getTasks(null, null).isEmpty());

        List<WarehouseTaskResponse> tasks = service.getTasks(null, null, true);

        assertEquals(2, tasks.size());
        assertEquals("TRIP-10", tasks.get(0).getTripCode());
        assertTrue(tasks.get(0).getAwaitingReplan());
        assertEquals("STAGING_SHORTAGE", tasks.get(0).getReplanReason());
        assertEquals("DRAFT", tasks.get(1).getStatus());
        assertEquals("DAMAGED_PACKAGE", tasks.get(1).getReplanReason());
    }

    @Test
    void tripCancelledWhileLoadingIsNotWaitingForAReplan() {
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.PLANNED)).thenReturn(List.of(trip));
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.LOADING)).thenReturn(List.of());
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.DRAFT)).thenReturn(List.of());
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.empty());
        when(loadingExecutionRepository.findAllByLoadPlanJobTripId(10L)).thenReturn(List.of(
                LoadingExecution.builder().id(60L).status(LoadingExecutionStatus.ABORTED).build()));

        assertTrue(service.getTasks(null, null, true).isEmpty());
    }

    @Test
    void sessionReportsStagingDamageAndManualState() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage first = CargoPackage.builder().company(trip.getCompany()).id(1L).build();
        CargoPackage second = CargoPackage.builder().company(trip.getCompany()).id(2L).build();
        PackagePlacement p1 = PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(first).stepSequence(1).build();
        PackagePlacement p2 = PackagePlacement.builder().id(72L).loadPlan(plan).cargoPackage(second).stepSequence(2).build();
        LoadingExecution execution = LoadingExecution.builder().id(60L).loadPlan(plan)
                .status(LoadingExecutionStatus.IN_PROGRESS).stagingRequired(true).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(p1, p2));
        when(placementConfirmationRepository.findByPlacementLoadPlanId(50L)).thenReturn(List.of());
        when(deviationRepository.findByPlacementLoadPlanId(50L)).thenReturn(List.of());
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of());
        ManualUnloadConfirmation manual = ManualUnloadConfirmation.builder().cargoPackage(first)
                .status(ManualConfirmStatus.PENDING).step(ManualConfirmStep.STAGING).build();
        when(warehouseProgress.of(10L, 50L)).thenReturn(new WarehouseProgress.State(
                Map.of(1L, StagingConfirmation.builder().cargoPackage(first).method(StagingMethod.MANUAL).build()),
                Map.of(2L, StagingShortage.builder().cargoPackage(second).status(ShortageStatus.OPEN).build()),
                Set.of(72L),
                Map.of(1L, manual)));

        StartLoadingResponse session = service.getSession(10L);

        assertEquals(List.of("PENDING", "DAMAGED"),
                session.getPlacements().stream().map(PlacementStepResponse::getStatus).toList());
        assertEquals(1, session.getDamagedCount());
        assertEquals(1, session.getPendingCount());
        assertEquals(1, session.getStagedCount());
        assertEquals(1, session.getOpenShortageCount());
        assertEquals(1, session.getPendingManualCount());
        assertFalse(session.getStagingComplete());
        PlacementStepResponse step = session.getPlacements().get(0);
        assertTrue(step.getStaged());
        assertEquals("MANUAL", step.getStagingMethod());
        assertEquals("PENDING", step.getManualStatus());
        assertEquals("STAGING", step.getManualStep());
        assertEquals("OPEN", session.getPlacements().get(1).getShortageStatus());
    }

    @Test
    void confirmingWaitsForStaging() {
        PackagePlacement placement = loadingPlacement(71L, 1);
        doThrow(new AppException(ErrorCode.STAGING_INCOMPLETE))
                .when(warehouseProgress).requireStagingComplete(any(), any(), any());

        AppException error = assertThrows(AppException.class, () -> service.confirmPlacement(placement.getId(), null, label(placement.getId())));

        assertEquals(ErrorCode.STAGING_INCOMPLETE, error.getErrorCode());
        verify(placementConfirmationRepository, never()).save(any());
    }

    @Test
    void packageLeftAtTheWarehouseDoesNotBlockTheNextPlacement() {
        PackagePlacement damaged = loadingPlacement(71L, 1);
        PackagePlacement next = loadingPlacement(72L, 2);
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(damaged, next));
        lenient().when(warehouseProgress.isLeftOut(71L)).thenReturn(true);
        when(placementConfirmationRepository.save(any())).thenAnswer(invocation -> {
            PlacementConfirmation saved = invocation.getArgument(0);
            saved.setId(90L);
            return saved;
        });

        assertEquals("CONFIRMED", service.confirmPlacement(72L, null, label(72L)).getStatus());
    }

    @Test
    void confirmingAPlacementRecordsHowTheLabelWasChecked() {
        PackagePlacement first = loadingPlacement(71L, 1);
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(first));
        when(placementConfirmationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirmPlacement(71L, null, label(71L));

        org.mockito.ArgumentCaptor<PlacementConfirmation> saved = org.mockito.ArgumentCaptor.forClass(PlacementConfirmation.class);
        verify(placementConfirmationRepository).save(saved.capture());
        assertEquals(LabelMethod.CODE, saved.getValue().getMethod());
    }

    @Test
    void confirmingAPlacementWithQrRecordsScannedMethod() {
        PackagePlacement first = loadingPlacement(71L, 1);
        first.getCargoPackage().setQrToken("QR-71");
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(first));
        when(placementConfirmationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.confirmPlacement(71L, null, new fu.se184491.loadmaster_be.dto.request.warehouse.ConfirmPlacementRequest(10L, StagingMethod.QR, "QR-71"));

        org.mockito.ArgumentCaptor<PlacementConfirmation> saved = org.mockito.ArgumentCaptor.forClass(PlacementConfirmation.class);
        verify(placementConfirmationRepository).save(saved.capture());
        assertEquals(LabelMethod.QR, saved.getValue().getMethod());
    }

    @Test
    void completingWaitsForWarehouseManualConfirmations() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage pkg = CargoPackage.builder().company(trip.getCompany()).id(1L).status(PackageStatus.LOADED).build();
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan).cargoPackage(pkg).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(placement));
        when(placementConfirmationRepository.existsByPlacementId(70L)).thenReturn(true);
        when(warehouseProgress.of(10L, 50L)).thenReturn(new WarehouseProgress.State(Map.of(), Map.of(), Set.of(),
                Map.of(1L, ManualUnloadConfirmation.builder().cargoPackage(pkg).status(ManualConfirmStatus.PENDING)
                        .step(ManualConfirmStep.LOADING).build())));

        AppException error = assertThrows(AppException.class, () -> service.completeLoading(10L));

        assertEquals(ErrorCode.LOADING_MANUAL_CONFIRM_PENDING, error.getErrorCode());
        assertEquals(LoadingExecutionStatus.IN_PROGRESS, execution.getStatus());
    }

    @Test
    void completingSkipsThePackageLeftAtTheWarehouse() {
        trip.setStatus(TripStatus.LOADING);
        CargoPackage loaded = CargoPackage.builder().company(trip.getCompany()).id(1L).status(PackageStatus.PLANNED).build();
        CargoPackage leftOut = CargoPackage.builder().company(trip.getCompany()).id(2L).status(PackageStatus.PENDING).build();
        PackagePlacement p1 = PackagePlacement.builder().id(70L).loadPlan(plan).cargoPackage(loaded).build();
        PackagePlacement p2 = PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(leftOut).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(p1, p2));
        when(placementConfirmationRepository.existsByPlacementId(70L)).thenReturn(true);
        lenient().when(warehouseProgress.isLeftOut(71L)).thenReturn(true);

        CompleteLoadingResponse response = service.completeLoading(10L);

        assertEquals(1, response.getTotalPackages());
        assertEquals(PackageStatus.LOADED, loaded.getStatus());
        assertEquals(PackageStatus.PENDING, leftOut.getStatus());
    }

    private fu.se184491.loadmaster_be.dto.request.warehouse.ConfirmPlacementRequest label(Long id) {
        return new fu.se184491.loadmaster_be.dto.request.warehouse.ConfirmPlacementRequest(10L, StagingMethod.CODE, "PK-" + id);
    }

    /** Vị trí của chuyến đang xếp, phiên đang mở. */
    private PackagePlacement loadingPlacement(Long id, int sequence) {
        trip.setStatus(TripStatus.LOADING);
        plan.setJob(OptimizationJob.builder().trip(trip).build());
        PackagePlacement placement = PackagePlacement.builder().id(id).loadPlan(plan)
                .cargoPackage(CargoPackage.builder().company(trip.getCompany()).id(id).status(PackageStatus.PLANNED).packageCode("PK-" + id).build())
                .stepSequence(sequence).build();
        lenient().when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        lenient().when(packagePlacementRepository.findById(id)).thenReturn(Optional.of(placement));
        lenient().when(loadingExecutionRepository.existsByLoadPlanIdAndStatus(50L, LoadingExecutionStatus.IN_PROGRESS))
                .thenReturn(true);
        lenient().when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build()));
        lenient().when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(placement));
        return placement;
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
        CargoPackage pkg = CargoPackage.builder().company(trip.getCompany()).id(1L).status(PackageStatus.PLANNED).build();
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan).cargoPackage(pkg).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(placement));
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
                        .doorWidth(230).doorHeight(230).maxPayloadKg(new BigDecimal("8000")).build())
                .build());
        trip.getVehicle().getVehicleType().setObstacles(List.of(
                fu.se184491.loadmaster_be.entity.vehicle.VehicleTypeObstacle.builder()
                        .id(81L).name("Wheel well")
                        .type(fu.se184491.loadmaster_be.constant.vehicle.ObstacleType.WHEEL_ARCH)
                        .x(new BigDecimal("360")).y(BigDecimal.ZERO).z(BigDecimal.ZERO)
                        .length(100).width(35).height(45).loadBearing(false).build()));
        CargoPackage first = CargoPackage.builder().company(trip.getCompany()).id(1L).packageCode("DH-001-001")
                .trackingBarcode("DH-001-001").qrToken("token-1").actualWeightKg(new BigDecimal("12.5")).build();
        CargoPackage second = CargoPackage.builder().company(trip.getCompany()).id(2L).packageCode("DH-001-002").qrToken("token-2")
                .packageType(fu.se184491.loadmaster_be.entity.cargo.PackageType.builder()
                        .name("Thùng carton").weightKg(new BigDecimal("7")).build())
                .build();
        CargoPackage third = CargoPackage.builder().company(trip.getCompany()).id(3L).packageCode("DH-001-003").qrToken("token-3").build();
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
        assertEquals(new BigDecimal("8000"), session.getMaxPayloadKg());
        assertEquals(1, session.getObstacles().size());
        var obstacle = session.getObstacles().get(0);
        assertEquals(81L, obstacle.id());
        assertEquals(new BigDecimal("360"), obstacle.x());
        assertEquals(100, obstacle.length());
        assertEquals(35, obstacle.width());
        assertEquals(45, obstacle.height());
        assertEquals(false, obstacle.loadBearing());
        assertEquals(new BigDecimal("12.5"), steps.get(0).getWeightKg());
        assertEquals(new BigDecimal("7"), steps.get(1).getWeightKg());
        assertEquals(null, steps.get(2).getWeightKg());
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
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan)
                .cargoPackage(CargoPackage.builder().id(1L).company(trip.getCompany()).build()).build();
        LoadingExecution execution = LoadingExecution.builder()
                .id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        when(packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(placement));

        assertThrows(fu.se184491.loadmaster_be.exception.IncompleteLoadingException.class,
                () -> service.completeLoading(10L));
    }
    @Test void startLoadingRevalidatesVehicleBeforeWritingExecution() {
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.VEHICLE_DEPARTURE_INELIGIBLE))
            .when(vehicleEligibility).requireLocked(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.<Long>any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.<Long>any());
        assertThrows(AppException.class, () -> service.startLoading(10L, null));
        verify(loadingExecutionRepository, never()).save(any());
        verify(tripStatusService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void tasksAndSessionCarryVehicleOriginAndPresenceWithoutWriting() {
        trip.setStatus(TripStatus.LOADING);
        trip.setOriginWarehouse(fu.se184491.loadmaster_be.entity.company.CompanyWarehouse.builder().id(101L).build());
        when(presenceStore.presence(3L, 1L)).thenReturn(
                new fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore.Presence(3L, 1L, null, "IN_TRANSIT", null, null, null, 2));
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.PLANNED)).thenReturn(List.of());
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.LOADING)).thenReturn(List.of(trip));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(
                LoadingExecution.builder().id(60L).loadPlan(plan).status(LoadingExecutionStatus.COMPLETED).build()));

        WarehouseTaskResponse task = service.getTasks(null, null).getFirst();
        StartLoadingResponse session = service.getSession(10L);

        var inTransit = new fu.se184491.loadmaster_be.dto.response.warehouse.VehiclePresenceSummary("IN_TRANSIT", null);
        assertEquals(3L, task.getVehicleId());
        assertEquals(101L, task.getOriginWarehouseId());
        assertEquals(inTransit, task.getVehiclePresence());
        assertEquals(3L, session.getVehicleId());
        assertEquals(101L, session.getOriginWarehouseId());
        assertEquals(inTransit, session.getVehiclePresence());
        verify(presenceStore, never()).change(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void tripWithoutVehicleOrOriginHasNoPresence() {
        trip.setVehicle(null);
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.PLANNED)).thenReturn(List.of(trip));
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.LOADING)).thenReturn(List.of());
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));

        WarehouseTaskResponse task = service.getTasks(null, null).getFirst();

        assertNull(task.getVehicleId());
        assertNull(task.getOriginWarehouseId());
        assertNull(task.getVehiclePresence());
        verify(presenceStore, never()).presence(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }
}
