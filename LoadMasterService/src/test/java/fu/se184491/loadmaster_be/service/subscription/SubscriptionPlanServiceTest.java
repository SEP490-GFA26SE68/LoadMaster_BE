package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.config.SubscriptionPlanInitializer;
import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
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
    @DisplayName("Update plan should succeed and SubscriptionPlan/DTOs should not expose redundant maxVehicles or maxMonthlyJobs")
    void updatePlan_success() {
        request.setMonthlyCredits(15000);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.existsByTierAndActiveTrueAndIdNot(SubscriptionTier.PRO, 1L)).thenReturn(false);
        when(planRepository.save(any(SubscriptionPlan.class))).thenReturn(plan);

        SubscriptionPlanResponse response = planService.updatePlan(1L, request);

        assertNotNull(response);
        assertEquals(15000, plan.getMonthlyCredits());
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlan.class.getDeclaredField("maxVehicles"));
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlan.class.getDeclaredField("maxMonthlyJobs"));
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlanRequest.class.getDeclaredField("maxVehicles"));
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlanRequest.class.getDeclaredField("maxMonthlyJobs"));
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlanResponse.class.getDeclaredField("maxVehicles"));
        assertThrows(NoSuchFieldException.class, () -> SubscriptionPlanResponse.class.getDeclaredField("maxMonthlyJobs"));
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
        verify(planRepository, never()).delete(any());
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
    @DisplayName("SubscriptionPlanInitializer should seed 3 default plans (3,000 / 15,000 / 30,000 credits) when missing")
    void seedDefaultPlans_whenEmpty() throws Exception {
        when(planRepository.existsByPlanCode("BASIC_MONTHLY")).thenReturn(false);
        when(planRepository.existsByPlanCode("PRO_MONTHLY")).thenReturn(false);
        when(planRepository.existsByPlanCode("ULTIMATE_MONTHLY")).thenReturn(false);
        SubscriptionPlanInitializer initializer = new SubscriptionPlanInitializer(planRepository);

        initializer.run(null);

        verify(planRepository, times(3)).save(any(SubscriptionPlan.class));
        verify(planRepository).save(argThat(p -> p.getTier() == SubscriptionTier.BASIC && Integer.valueOf(3000).equals(p.getMonthlyCredits())));
        verify(planRepository).save(argThat(p -> p.getTier() == SubscriptionTier.PRO && Integer.valueOf(15000).equals(p.getMonthlyCredits())));
        verify(planRepository).save(argThat(p -> p.getTier() == SubscriptionTier.ULTIMATE && Integer.valueOf(30000).equals(p.getMonthlyCredits())));
    }
}
