package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanComparisonResponse;

public interface LoadPlanService {

    PackagePlacementResponse pinPlacement(Long loadPlanId, PinPlacementRequest request);

    PackagePlacementResponse unpinPlacement(Long loadPlanId, Long placementId);

    LoadPlanResponse rerunOptimization(Long loadPlanId, RerunOptimizationRequest request);

    PlanComparisonResponse comparePlans(Long planId1, Long planId2);
}
