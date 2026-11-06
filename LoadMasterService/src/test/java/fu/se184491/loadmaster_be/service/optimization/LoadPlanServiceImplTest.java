package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.optimization.UnplacedPackageRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.Impl.LoadPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadPlanServiceImplTest {

    @Mock private LoadPlanRepository loadPlanRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private OptimizationJobRepository optimizationJobRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private UnplacedPackageRepository unplacedPackageRepository;
    @Mock private OptimizationEngineClient optimizationEngineClient;
    @Mock private CreditService creditService;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private CurrentUserService currentUserService;

    @InjectMocks
    private LoadPlanServiceImpl service;

    private Trip trip;
    private LoadPlan plan;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        trip = Trip.builder().id(10L).company(company).status(TripStatus.PLANNED).build();
        OptimizationJob job = OptimizationJob.builder().id(20L).trip(trip).build();
        plan = LoadPlan.builder().id(50L).job(job).version(1).build();
        when(loadPlanRepository.findById(50L)).thenReturn(Optional.of(plan));
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
    }

    @Test
    void pinningInPlannedTripPinsPlacement() {
        PackagePlacement placement = PackagePlacement.builder().id(70L).loadPlan(plan).pinned(false).build();
        when(packagePlacementRepository.findById(70L)).thenReturn(Optional.of(placement));
        when(packagePlacementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PackagePlacementResponse response = service.pinPlacement(50L, PinPlacementRequest.builder().placementId(70L).build());

        assertTrue(response.getPinned());
    }

    @Test
    void pinningAfterLoadingStartedIsLocked() {
        trip.setStatus(TripStatus.LOADING);

        AppException error = assertThrows(AppException.class,
                () -> service.pinPlacement(50L, PinPlacementRequest.builder().placementId(70L).build()));

        assertEquals(ErrorCode.TRIP_LOCKED, error.getErrorCode());
        verify(packagePlacementRepository, never()).save(any());
    }

    @Test
    void unpinningInDraftTripIsLocked() {
        trip.setStatus(TripStatus.DRAFT);

        AppException error = assertThrows(AppException.class, () -> service.unpinPlacement(50L, 70L));

        assertEquals(ErrorCode.TRIP_LOCKED, error.getErrorCode());
    }

    @Test
    void planOfAnotherCompanyIsNotFound() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(2L);

        AppException error = assertThrows(AppException.class, () -> service.unpinPlacement(50L, 70L));

        assertEquals(ErrorCode.LOAD_PLAN_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void rerunAfterDepartureIsLockedAndSpendsNoCredit() {
        trip.setStatus(TripStatus.IN_TRANSIT);

        AppException error = assertThrows(AppException.class,
                () -> service.rerunOptimization(50L, new RerunOptimizationRequest()));

        assertEquals(ErrorCode.TRIP_LOCKED, error.getErrorCode());
        verify(creditService, never()).deductCredit(anyLong(), any(), anyInt());
        verify(optimizationEngineClient, never()).rerunOptimization(any());
    }
}
