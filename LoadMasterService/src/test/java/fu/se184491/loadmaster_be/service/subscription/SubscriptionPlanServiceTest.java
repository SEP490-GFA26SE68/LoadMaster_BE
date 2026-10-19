package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscriptionPlanRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.service.subscription.Impl.SubscriptionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SubscriptionPlanServiceTest {

    @Mock
    private SubscriptionPlanRepository planRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @InjectMocks
    private SubscriptionPlanServiceImpl planService;

    private SubscriptionPlanRequest request;
    private SubscriptionPlan plan;

    @BeforeEach
    void setUp() {
        request = SubscriptionPlanRequest.builder()
                .planCode("PLAN-PRO")
                .name("Pro Tier")
                .priceVnd(BigDecimal.valueOf(500000))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(500)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .billingCycle(BillingCycle.MONTHLY)
                .active(true)
                .build();

        plan = SubscriptionPlan.builder()
                .id(1L)
                .planCode("PLAN-PRO")
                .name("Pro Tier")
                .price(BigDecimal.valueOf(500000))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(500)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .billingCycle(BillingCycle.MONTHLY)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("Get all active plans should return list of plans")
    void getActivePlans_success() {
        when(planRepository.findByActiveTrue()).thenReturn(List.of(plan));

        List<SubscriptionPlanResponse> responses = planService.getActivePlans();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        assertEquals("PLAN-PRO", responses.get(0).getPlanCode());
        assertEquals(SubscriptionTier.PRO, responses.get(0).getTier());
    }

    @Test
    @DisplayName("Create plan should succeed when code and tier are unique")
    void createPlan_success() {
        when(planRepository.existsByPlanCode("PLAN-PRO")).thenReturn(false);
        when(planRepository.existsByTierAndActiveTrue(SubscriptionTier.PRO)).thenReturn(false);
        when(planRepository.save(any(SubscriptionPlan.class))).thenReturn(plan);

        SubscriptionPlanResponse response = planService.createPlan(request);

        assertNotNull(response);
        assertEquals("PLAN-PRO", response.getPlanCode());
        assertEquals(SubscriptionTier.PRO, response.getTier());
        verify(planRepository).save(any(SubscriptionPlan.class));
    }

    @Test
    @DisplayName("Create plan should throw SUBSCRIPTION_PLAN_ALREADY_EXISTS when code exists")
    void createPlan_duplicateCode() {
        when(planRepository.existsByPlanCode("PLAN-PRO")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> planService.createPlan(request));
        assertEquals(ErrorCode.SUBSCRIPTION_PLAN_ALREADY_EXISTS, ex.getErrorCode());
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create plan should throw SUBSCRIPTION_TIER_ALREADY_EXISTS when active tier exists")
    void createPlan_duplicateTier() {
        when(planRepository.existsByPlanCode("PLAN-PRO")).thenReturn(false);
        when(planRepository.existsByTierAndActiveTrue(SubscriptionTier.PRO)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> planService.createPlan(request));
        assertEquals(ErrorCode.SUBSCRIPTION_TIER_ALREADY_EXISTS, ex.getErrorCode());
        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("Update plan should succeed when plan exists and tier unique")
    void updatePlan_success() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.existsByTierAndActiveTrueAndIdNot(SubscriptionTier.PRO, 1L)).thenReturn(false);
        when(planRepository.save(any(SubscriptionPlan.class))).thenReturn(plan);

        SubscriptionPlanResponse response = planService.updatePlan(1L, request);

        assertNotNull(response);
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("Delete plan should succeed when no company is actively using it")
    void deletePlan_success() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(companySubscriptionRepository.existsByPlanIdAndStatus(1L, SubscriptionStatus.ACTIVE)).thenReturn(false);
        doNothing().when(planRepository).delete(plan);

        assertDoesNotThrow(() -> planService.deletePlan(1L));
        verify(planRepository).delete(plan);
    }

    @Test
    @DisplayName("Delete plan should throw PLAN_IN_USE when active subscriptions exist")
    void deletePlan_inUse() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(companySubscriptionRepository.existsByPlanIdAndStatus(1L, SubscriptionStatus.ACTIVE)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> planService.deletePlan(1L));
        assertEquals(ErrorCode.PLAN_IN_USE, ex.getErrorCode());
        verify(planRepository, never()).delete(any(SubscriptionPlan.class));
    }

    @Test
    @DisplayName("getActivePlans and getPlanById should populate activeCompanyCount from repository")
    void getActivePlans_populatesActiveCompanyCount() {
        when(planRepository.findByActiveTrue()).thenReturn(List.of(plan));
        when(companySubscriptionRepository.countByPlanIdAndStatus(1L, SubscriptionStatus.ACTIVE)).thenReturn(4L);

        List<SubscriptionPlanResponse> responses = planService.getActivePlans();

        assertEquals(1, responses.size());
        assertEquals(4L, responses.get(0).getActiveCompanyCount());
        verify(companySubscriptionRepository).countByPlanIdAndStatus(1L, SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("getAllPlans with pagination and filter should return PageResponse with activeCompanyCount")
    void getAllPlans_paged_success() {
        Page<SubscriptionPlan> pageResult = new PageImpl<>(List.of(plan), PageRequest.of(0, 10), 1);
        when(planRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(pageResult);
        when(companySubscriptionRepository.countByPlanIdAndStatus(1L, SubscriptionStatus.ACTIVE)).thenReturn(2L);

        PageResponse<SubscriptionPlanResponse> response = planService.getAllPlans("PRO", true, SubscriptionTier.PRO, PageRequest.of(0, 10));

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals(1, response.getTotalElements());
        assertEquals(2L, response.getData().get(0).getActiveCompanyCount());
        verify(planRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    @DisplayName("Update plan should update maxVehicles and maxMonthlyJobs")
    void updatePlan_updatesMaxVehiclesAndMaxMonthlyJobs() {
        SubscriptionPlanRequest updateReq = SubscriptionPlanRequest.builder()
                .planCode("PLAN-PRO")
                .name("Pro Tier Updated")
                .priceVnd(BigDecimal.valueOf(600000))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(600)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA_AI)
                .billingCycle(BillingCycle.MONTHLY)
                .maxVehicles(25)
                .maxMonthlyJobs(150)
                .active(true)
                .build();

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.existsByTierAndActiveTrueAndIdNot(SubscriptionTier.PRO, 1L)).thenReturn(false);
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubscriptionPlanResponse response = planService.updatePlan(1L, updateReq);

        assertNotNull(response);
        assertEquals(25, response.getMaxVehicles());
        assertEquals(150, response.getMaxMonthlyJobs());
        assertEquals(25, plan.getMaxVehicles());
        assertEquals(150, plan.getMaxMonthlyJobs());
    }
}
