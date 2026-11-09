package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordSealRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportDamageRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DamageResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.SealResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.LoadingDamage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.warehouse.Impl.WarehouseIncidentServiceImpl;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseIncidentServiceImplTest {

    @Mock private WarehouseSessionLookup lookup;
    @Mock private PlacementConfirmationRepository placementConfirmationRepository;
    @Mock private DeviationRepository deviationRepository;
    @Mock private LoadingDamageRepository loadingDamageRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private ManualUnloadConfirmationRepository manualConfirmationRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private LoadingReplanService loadingReplanService;

    @InjectMocks
    private WarehouseIncidentServiceImpl service;

    private User worker;
    private Trip trip;
    private LoadPlan plan;
    private LoadingExecution execution;
    private CargoPackage bottomPkg;
    private PackagePlacement bottom;
    private PackagePlacement top;
    private PackagePlacement aside;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        worker = User.builder().id(9L).company(company).fullName("Kho A").build();
        trip = Trip.builder().id(10L).company(company).status(TripStatus.LOADING).build();
        plan = LoadPlan.builder().id(50L).build();
        execution = LoadingExecution.builder().id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        bottomPkg = CargoPackage.builder().id(1L).status(PackageStatus.PLANNED).build();
        bottom = PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(bottomPkg).stepSequence(1)
                .posX(0).posY(0).posZ(0).packedLength(100).packedWidth(100).packedHeight(50).build();
        top = PackagePlacement.builder().id(72L).loadPlan(plan).cargoPackage(CargoPackage.builder().id(2L).build())
                .stepSequence(2).posX(10).posY(10).posZ(50).packedLength(50).packedWidth(50).packedHeight(50).build();
        aside = PackagePlacement.builder().id(73L).loadPlan(plan).cargoPackage(CargoPackage.builder().id(3L).build())
                .stepSequence(2).posX(200).posY(0).posZ(0).packedLength(50).packedWidth(50).packedHeight(50).build();
    }

    private Session session(PackagePlacement... placements) {
        Session session = new Session(worker, trip, plan, execution, List.of(placements));
        lenient().when(lookup.activeSessionOfPlacement(any())).thenReturn(session);
        for (PackagePlacement placement : placements) {
            lenient().when(lookup.placement(session, placement.getId())).thenReturn(placement);
        }
        return session;
    }

    @Test
    void damagedPackageWithNothingOnTopIsLeftAtTheWarehouse() {
        session(bottom, aside);
        when(loadingDamageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DamageResponse response = service.reportDamaged(71L, new ReportDamageRequest("Móp góc"));

        assertEquals("LEFT_OUT", response.getOutcome());
        assertEquals(0, response.getRestingCount());
        assertEquals("LOADING", response.getTripStatus());
        assertEquals(73L, response.getNextPlacement().getPlacementId());
        verify(loadingReplanService).leaveOut(trip, bottomPkg, PackageFlag.DAMAGED);
        verify(loadingReplanService, never()).returnToPlanning(any(), any(), any(), any(), any());
    }

    @Test
    void damagedPackageCarryingAnotherSendsTheTripBackToPlanning() {
        session(bottom, top);
        when(loadingDamageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DamageResponse response = service.reportDamaged(71L, null);

        assertEquals("REPLANNED", response.getOutcome());
        assertEquals(1, response.getRestingCount());
        ArgumentCaptor<LoadingDamage> damage = ArgumentCaptor.forClass(LoadingDamage.class);
        verify(loadingDamageRepository).save(damage.capture());
        assertEquals(DamageOutcome.REPLANNED, damage.getValue().getOutcome());
        verify(loadingReplanService).returnToPlanning(trip, execution, bottomPkg, PackageFlag.DAMAGED, worker);
    }

    @Test
    void onlyTheNextPlacementCanBeReportedDamaged() {
        Session session = session(bottom, top);
        doThrow(new AppException(ErrorCode.INVALID_SEQUENCE_ORDER)).when(lookup).requireInSequence(session, top);

        AppException error = assertThrows(AppException.class, () -> service.reportDamaged(72L, null));

        assertEquals(ErrorCode.INVALID_SEQUENCE_ORDER, error.getErrorCode());
        verify(loadingDamageRepository, never()).save(any());
    }

    @Test
    void manualLoadingCountsAsLoadedAndWaitsForTheDispatcher() {
        session(bottom);
        when(lookup.stopOf(bottom)).thenReturn(DeliveryStop.builder().id(30L).build());
        when(manualConfirmationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualConfirmationResponse response = service.confirmPlacementManually(71L,
                new ManualUnloadRequest(ManualConfirmReason.QR_UNREADABLE, null));

        assertEquals("LOADING", response.getStep());
        assertEquals(71L, response.getPlacementId());
        assertEquals("PENDING", response.getStatus());
        assertEquals(PackageStatus.LOADED, bottomPkg.getStatus());
        verify(placementConfirmationRepository).save(any());
        ArgumentCaptor<ManualUnloadConfirmation> manual = ArgumentCaptor.forClass(ManualUnloadConfirmation.class);
        verify(manualConfirmationRepository).save(manual.capture());
        assertEquals(ManualConfirmStep.LOADING, manual.getValue().getStep());
    }

    @Test
    void manualLoadingWithOtherReasonNeedsANote() {
        AppException error = assertThrows(AppException.class, () -> service.confirmPlacementManually(71L,
                new ManualUnloadRequest(ManualConfirmReason.OTHER, "  ")));

        assertEquals(ErrorCode.MANUAL_CONFIRM_NOTE_REQUIRED, error.getErrorCode());
    }

    @Test
    void sealIsRecordedAfterLoadingIsCompleted() {
        execution.setStatus(LoadingExecutionStatus.COMPLETED);
        when(lookup.session(10L)).thenReturn(new Session(worker, trip, plan, execution, List.of(bottom)));

        SealResponse response = service.recordSeal(10L, new RecordSealRequest("  SEAL-0042 "));

        assertEquals("SEAL-0042", response.getSealNumber());
        assertEquals("SEAL-0042", execution.getSealNumber());
        assertEquals(worker, execution.getSealedBy());
        verify(loadingExecutionRepository).save(execution);
    }

    @Test
    void sealBeforeLoadingIsCompletedIsRejected() {
        when(lookup.session(10L)).thenReturn(new Session(worker, trip, plan, execution, List.of(bottom)));

        AppException error = assertThrows(AppException.class, () -> service.recordSeal(10L, new RecordSealRequest("S-1")));

        assertEquals(ErrorCode.SEAL_NOT_ALLOWED, error.getErrorCode());
    }

    @Test
    void sealAfterDepartureIsRejected() {
        when(lookup.session(10L)).thenThrow(new AppException(ErrorCode.TRIP_NOT_LOADING));

        AppException error = assertThrows(AppException.class, () -> service.recordSeal(10L, new RecordSealRequest("S-1")));

        assertEquals(ErrorCode.SEAL_NOT_ALLOWED, error.getErrorCode());
    }

    @Test
    void emptyOrTooLongSealIsInvalid() {
        assertEquals(ErrorCode.SEAL_INVALID, assertThrows(AppException.class,
                () -> service.recordSeal(10L, new RecordSealRequest("   "))).getErrorCode());
        assertEquals(ErrorCode.SEAL_INVALID, assertThrows(AppException.class,
                () -> service.recordSeal(10L, new RecordSealRequest("x".repeat(33)))).getErrorCode());
    }
}
