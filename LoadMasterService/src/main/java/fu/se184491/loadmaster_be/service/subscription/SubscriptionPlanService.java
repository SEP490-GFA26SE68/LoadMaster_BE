package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscriptionPlanRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface SubscriptionPlanService {
    List<SubscriptionPlanResponse> getActivePlans();
    PageResponse<SubscriptionPlanResponse> getAllPlans(String search, Boolean active, SubscriptionTier tier, Pageable pageable);
    SubscriptionPlanResponse getPlanById(Long id);
    SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request);
    SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request);
    void deletePlan(Long id);
}
