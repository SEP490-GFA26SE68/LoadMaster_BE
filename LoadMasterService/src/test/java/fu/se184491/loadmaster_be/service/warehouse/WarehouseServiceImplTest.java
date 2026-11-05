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
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
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
}
