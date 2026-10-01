package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.dto.request.subscription.SubscriptionPlanRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;

import java.util.List;

public interface SubscriptionPlanService {
    List<SubscriptionPlanResponse> getActivePlans();
    SubscriptionPlanResponse getPlanById(Long id);
    SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request);
    SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request);
    void deletePlan(Long id);
}
