package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationAlgorithm;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    @Mock private fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository companySubscriptionRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

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

    private LoadPlanResponse rerunWith(String engineAlgorithm) {
        // Lần chạy trước theo Basic, công ty nay đã lên Pro: chạy lại theo hạng hiện tại
        plan.getJob().setAlgorithmName(OptimizationAlgorithm.EP_DBLF);
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(
                fu.se184491.loadmaster_be.entity.billing.CompanySubscription.builder()
                        .status(fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus.ACTIVE)
                        .plan(fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan.builder()
                                .tier(fu.se184491.loadmaster_be.constant.billing.SubscriptionTier.PRO).build())
                        .build()));
        when(optimizationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(loadPlanRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(optimizationEngineClient.rerunOptimization(any())).thenReturn(OptimizationResultDto.builder()
                .packedItemsCount(3).volumeUtilization(BigDecimal.ONE).weightUtilization(BigDecimal.ONE)
                .algorithmName(engineAlgorithm).build());
        return service.rerunOptimization(50L, new RerunOptimizationRequest());
    }

    @Test
    void rerunKeepsTheAlgorithmTheEngineReportsBesideTheRunTier() {
        LoadPlanResponse response = rerunWith("SA");

        assertEquals("SA", response.getAlgorithmName());
        assertEquals("EP_DBLF_GA", response.getRunAlgorithm());
        ArgumentCaptor<LoadPlan> saved = ArgumentCaptor.forClass(LoadPlan.class);
        verify(loadPlanRepository).save(saved.capture());
        assertEquals("SA", saved.getValue().getAlgorithmName());
    }

    @Test
    void rerunSendsTheCompanyTierToTheEngine() {
        rerunWith("GA");

        ArgumentCaptor<fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest> sent =
                ArgumentCaptor.forClass(fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest.class);
        verify(optimizationEngineClient).rerunOptimization(sent.capture());
        assertEquals("PRO", sent.getValue().getSubscriptionTier());
        assertEquals("EP_DBLF_GA", sent.getValue().getAlgorithmName());
    }

    @Test
    void anExpiredSubscriptionRerunsOnTheBasicTier() {
        plan.getJob().setAlgorithmName(OptimizationAlgorithm.EP_DBLF_GA);
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(
                fu.se184491.loadmaster_be.entity.billing.CompanySubscription.builder()
                        .status(fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus.EXPIRED)
                        .plan(fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan.builder()
                                .tier(fu.se184491.loadmaster_be.constant.billing.SubscriptionTier.ULTIMATE).build())
                        .build()));
        when(optimizationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(loadPlanRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(optimizationEngineClient.rerunOptimization(any())).thenReturn(OptimizationResultDto.builder()
                .packedItemsCount(1).volumeUtilization(BigDecimal.ONE).weightUtilization(BigDecimal.ONE).build());

        LoadPlanResponse response = service.rerunOptimization(50L, new RerunOptimizationRequest());

        ArgumentCaptor<fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest> sent =
                ArgumentCaptor.forClass(fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest.class);
        verify(optimizationEngineClient).rerunOptimization(sent.capture());
        assertEquals("BASIC", sent.getValue().getSubscriptionTier());
        assertEquals("EP_DBLF", response.getRunAlgorithm());
    }

    @Test
    void engineFailureMarksTheJobFailedAndRefundsOutsideTheReserveTransaction() {
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());
        when(optimizationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(optimizationEngineClient.rerunOptimization(any())).thenThrow(new IllegalStateException("engine down"));

        assertThrows(IllegalStateException.class,
                () -> service.rerunOptimization(50L, new RerunOptimizationRequest()));

        ArgumentCaptor<OptimizationJob> jobs = ArgumentCaptor.forClass(OptimizationJob.class);
        verify(optimizationJobRepository, org.mockito.Mockito.atLeast(2)).save(jobs.capture());
        OptimizationJob job = jobs.getAllValues().get(jobs.getAllValues().size() - 1);
        assertEquals(fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus.FAILED, job.getStatus());
        verify(creditService).deductCredit(1L, job.getJobUuid(), 1);
        verify(creditService).refundCredit(1L, job.getJobUuid());
        // Giữ credit và lưu trạng thái lỗi là hai giao dịch đã commit, không cuộn lại cùng lỗi
        verify(transactionManager, org.mockito.Mockito.times(2)).commit(any());
        verify(loadPlanRepository, never()).save(any());
    }

    @Test
    void aFailedSaveAfterTheEngineRefundsTheCredit() {
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());
        when(optimizationJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(optimizationEngineClient.rerunOptimization(any())).thenReturn(OptimizationResultDto.builder()
                .packedItemsCount(1).volumeUtilization(BigDecimal.ONE).weightUtilization(BigDecimal.ONE).build());
        when(loadPlanRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> service.rerunOptimization(50L, new RerunOptimizationRequest()));

        verify(transactionManager).rollback(any());
        verify(creditService).refundCredit(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void rerunWithoutAnEngineAlgorithmFallsBackToTheRunTier() {
        LoadPlanResponse response = rerunWith(null);

        assertEquals("EP_DBLF_GA", response.getAlgorithmName());
        assertEquals("EP_DBLF_GA", response.getRunAlgorithm());
    }
}
