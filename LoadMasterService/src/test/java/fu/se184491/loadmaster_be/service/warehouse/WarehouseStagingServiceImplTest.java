package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageDecision;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ResolveShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StagePackageRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingShortageResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.warehouse.Impl.WarehouseStagingServiceImpl;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseStagingServiceImplTest {

    @Mock private WarehouseSessionLookup lookup;
    @Mock private WarehouseProgress warehouseProgress;
    @Mock private StagingConfirmationRepository stagingConfirmationRepository;
    @Mock private StagingShortageRepository stagingShortageRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private TripPackageResolver tripPackageResolver;
    @Mock private CurrentUserService currentUserService;
    @Mock private LoadingReplanService loadingReplanService;
    @Mock private StagingRecorder stagingRecorder;

    @InjectMocks
    private WarehouseStagingServiceImpl service;

    private User worker;
    private Trip trip;
    private CargoPackage pkg;
    private PackagePlacement placement;
    private Session session;
    private LoadingExecution execution;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        worker = User.builder().id(9L).company(company).fullName("Kho A").build();
        trip = Trip.builder().id(10L).tripCode("TRIP-10").company(company).status(TripStatus.LOADING).build();
        LoadPlan plan = LoadPlan.builder().id(50L).approved(true).build();
        pkg = CargoPackage.builder().id(1L).packageCode("DH-1").build();
        placement = PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(pkg).stepSequence(1).build();
        execution = LoadingExecution.builder().id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS)
                .stagingRequired(true).build();
        session = new Session(worker, trip, plan, execution, List.of(placement));
        lenient().when(lookup.activeSession(10L)).thenReturn(session);
        lenient().when(lookup.placementOfPackage(session, 1L)).thenReturn(placement);
        lenient().when(warehouseProgress.of(10L, 50L)).thenReturn(WarehouseProgress.State.empty());
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        lenient().when(tripLookupHelper.requireTrip("10", 1L)).thenReturn(trip);
    }

    @Test
    void stagingByLabelRecordsThePackageAndReplacesAPendingManualConfirmation() {
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L)).thenReturn(Optional.empty());
        StagePackageRequest request = new StagePackageRequest();
        request.setMethod(StagingMethod.CODE);

        StagingResponse response = service.stage(10L, 1L, request);

        assertFalse(response.isAlreadyStaged());
        assertEquals("CODE", response.getMethod());
        InOrder order = inOrder(stagingRecorder);
        order.verify(stagingRecorder).dropPendingManual(10L, 1L);
        order.verify(stagingRecorder).record(trip, pkg, StagingMethod.CODE, worker, worker);
    }

    @Test
    void stagingTheSamePackageAgainChangesNothing() {
        StagingConfirmation earlier = StagingConfirmation.builder().cargoPackage(pkg).method(StagingMethod.QR).build();
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L)).thenReturn(Optional.of(earlier));
        when(warehouseProgress.of(10L, 50L)).thenReturn(new WarehouseProgress.State(
                Map.of(1L, earlier), Map.of(), Set.of(), Map.of()));

        StagingResponse response = service.stage(10L, 1L, null);

        assertTrue(response.isAlreadyStaged());
        assertEquals("QR", response.getMethod());
        assertTrue(response.isStagingComplete());
        assertEquals(1, response.getStagedCount());
        verify(stagingConfirmationRepository, never()).save(any());
    }

    @Test
    void manualStagingIsNotStagedUntilTheDispatcherApproves() {
        DeliveryStop stop = DeliveryStop.builder().id(30L).build();
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L)).thenReturn(Optional.empty());
        when(lookup.stopOf(placement)).thenReturn(stop);
        when(manualConfirmationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ManualUnloadRequest request = new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED, null);

        service.stageManually(10L, 1L, request);

        ArgumentCaptor<ManualUnloadConfirmation> manual = ArgumentCaptor.forClass(ManualUnloadConfirmation.class);
        verify(manualConfirmationRepository).save(manual.capture());
        assertEquals(ManualConfirmStep.STAGING, manual.getValue().getStep());
        assertEquals(ManualConfirmStatus.PENDING, manual.getValue().getStatus());
        assertEquals(stop, manual.getValue().getDeliveryStop());
        verify(stagingRecorder).dropPendingManual(10L, 1L);
        verify(stagingRecorder, never()).record(any(), any(), any(), any(), any());
        verify(stagingConfirmationRepository, never()).save(any());
    }

    @Test
    void manualStagingOfAStagedPackageIsRejected() {
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L))
                .thenReturn(Optional.of(StagingConfirmation.builder().build()));

        AppException error = assertThrows(AppException.class, () -> service.stageManually(10L, 1L,
                new ManualUnloadRequest(ManualConfirmReason.QR_UNREADABLE, null)));

        assertEquals(ErrorCode.PACKAGE_ALREADY_STAGED, error.getErrorCode());
    }

    @Test
    void reportingAMissingPackageTwiceKeepsOneOpenShortage() {
        when(stagingConfirmationRepository.findByTripIdAndCargoPackageId(10L, 1L)).thenReturn(Optional.empty());
        StagingShortage open = StagingShortage.builder().id(5L).trip(trip).cargoPackage(pkg).status(ShortageStatus.OPEN).build();
        when(stagingShortageRepository.findFirstByTripIdAndCargoPackageIdAndStatus(10L, 1L, ShortageStatus.OPEN))
                .thenReturn(Optional.of(open));

        StagingShortageResponse response = service.reportShortage(10L, 1L, new ReportShortageRequest());

        assertEquals(5L, response.getId());
        verify(stagingShortageRepository, never()).save(any());
    }

    @Test
    void keepSearchingClosesTheReportWithoutTouchingTheTrip() {
        StagingShortage open = StagingShortage.builder().id(5L).trip(trip).cargoPackage(pkg).status(ShortageStatus.OPEN).build();
        when(stagingShortageRepository.findById(5L)).thenReturn(Optional.of(open));

        StagingShortageResponse response = service.resolveShortage("10", 5L,
                new ResolveShortageRequest(ShortageDecision.KEEP_SEARCHING));

        assertEquals("KEEP_SEARCHING", response.getStatus());
        verify(loadingReplanService, never()).returnToPlanning(any(), any(), any(), any(), any());
    }

    @Test
    void droppingSendsTheTripBackToPlanning() {
        StagingShortage open = StagingShortage.builder().id(5L).trip(trip).cargoPackage(pkg).status(ShortageStatus.OPEN).build();
        when(stagingShortageRepository.findById(5L)).thenReturn(Optional.of(open));
        when(loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L))
                .thenReturn(Optional.of(session.plan()));
        when(loadingExecutionRepository.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));

        StagingShortageResponse response = service.resolveShortage("10", 5L, new ResolveShortageRequest(ShortageDecision.DROP));

        assertEquals("DROPPED", response.getStatus());
        verify(loadingReplanService).returnToPlanning(trip, execution, pkg, PackageFlag.NOT_FOUND, null);
    }

    @Test
    void decidedShortageCannotBeDecidedAgain() {
        StagingShortage done = StagingShortage.builder().id(5L).trip(trip).cargoPackage(pkg)
                .status(ShortageStatus.KEEP_SEARCHING).build();
        when(stagingShortageRepository.findById(5L)).thenReturn(Optional.of(done));

        AppException error = assertThrows(AppException.class,
                () -> service.resolveShortage("10", 5L, new ResolveShortageRequest(ShortageDecision.DROP)));

        assertEquals(ErrorCode.STAGING_SHORTAGE_NOT_OPEN, error.getErrorCode());
    }

    @Test
    void shortageOfAnotherTripIsNotFound() {
        Trip other = Trip.builder().id(11L).build();
        when(stagingShortageRepository.findById(5L)).thenReturn(Optional.of(
                StagingShortage.builder().id(5L).trip(other).status(ShortageStatus.OPEN).build()));

        AppException error = assertThrows(AppException.class,
                () -> service.resolveShortage("10", 5L, new ResolveShortageRequest(ShortageDecision.DROP)));

        assertEquals(ErrorCode.STAGING_SHORTAGE_NOT_FOUND, error.getErrorCode());
    }
}
