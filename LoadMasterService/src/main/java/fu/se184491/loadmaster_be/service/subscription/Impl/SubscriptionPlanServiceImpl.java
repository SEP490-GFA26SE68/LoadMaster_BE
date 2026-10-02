package fu.se184491.loadmaster_be.service.subscription.Impl;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscriptionPlanRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionPlanServiceImpl implements SubscriptionPlanService {

    private final SubscriptionPlanRepository planRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> getActivePlans() {
        return planRepository.findByActiveTrue()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionPlanResponse getPlanById(Long id) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        return toResponse(plan);
    }

    @Override
    @Transactional
    public SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request) {
        if (planRepository.existsByPlanCode(request.getPlanCode())) {
            throw new AppException(ErrorCode.SUBSCRIPTION_PLAN_ALREADY_EXISTS);
        }
        if (request.getActive() != null && request.getActive() && planRepository.existsByTierAndActiveTrue(request.getTier())) {
            throw new AppException(ErrorCode.SUBSCRIPTION_TIER_ALREADY_EXISTS);
        }

        SubscriptionPlan plan = SubscriptionPlan.builder()
                .planCode(request.getPlanCode())
                .name(request.getName())
                .price(request.getPriceVnd())
                .tier(request.getTier())
                .monthlyCredits(request.getMonthlyCredits())
                .algorithmTier(request.getAlgorithmTier())
                .features(request.getFeatures())
                .billingCycle(request.getBillingCycle())
                .maxVehicles(request.getMaxVehicles())
                .maxMonthlyJobs(request.getMaxMonthlyJobs())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();

        SubscriptionPlan saved = planRepository.save(plan);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        if (request.getActive() != null && request.getActive() && planRepository.existsByTierAndActiveTrueAndIdNot(request.getTier(), id)) {
            throw new AppException(ErrorCode.SUBSCRIPTION_TIER_ALREADY_EXISTS);
        }

        plan.setName(request.getName());
        plan.setPrice(request.getPriceVnd());
        plan.setTier(request.getTier());
        plan.setMonthlyCredits(request.getMonthlyCredits());
        if (request.getAlgorithmTier() != null) {
            plan.setAlgorithmTier(request.getAlgorithmTier());
        }
        plan.setFeatures(request.getFeatures());
        if (request.getBillingCycle() != null) {
            plan.setBillingCycle(request.getBillingCycle());
        }
        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        }

        SubscriptionPlan updated = planRepository.save(plan);
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void deletePlan(Long id) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        if (companySubscriptionRepository.existsByPlanIdAndStatus(id, SubscriptionStatus.ACTIVE)) {
            throw new AppException(ErrorCode.PLAN_IN_USE);
        }

        planRepository.delete(plan);
    }

    private SubscriptionPlanResponse toResponse(SubscriptionPlan plan) {
        return SubscriptionPlanResponse.builder()
                .id(plan.getId())
                .planCode(plan.getPlanCode())
                .name(plan.getName())
                .priceVnd(plan.getPrice())
                .tier(plan.getTier())
                .monthlyCredits(plan.getMonthlyCredits())
                .algorithmTier(plan.getAlgorithmTier())
                .features(plan.getFeatures())
                .billingCycle(plan.getBillingCycle())
                .maxVehicles(plan.getMaxVehicles())
                .maxMonthlyJobs(plan.getMaxMonthlyJobs())
                .active(plan.getActive())
                .build();
    }
}
