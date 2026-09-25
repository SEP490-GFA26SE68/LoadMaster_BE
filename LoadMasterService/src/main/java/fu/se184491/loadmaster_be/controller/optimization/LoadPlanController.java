package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanComparisonResponse;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/load-plans")
@RequiredArgsConstructor
@Tag(name = "Load Plan", description = "APIs for managing load plans, placements, and pinning")
public class LoadPlanController {

    private final LoadPlanService loadPlanService;

    @PostMapping("/{id}/pin")
    @Operation(summary = "Pin placement", description = "Pin a placement in the load plan to fix its position for subsequent re-runs")
    public ResponseEntity<ApiResponse<PackagePlacementResponse>> pinPlacement(
            @PathVariable("id") Long id,
            @RequestBody @Valid PinPlacementRequest request) {
        PackagePlacementResponse response = loadPlanService.pinPlacement(id, request);
        return ResponseEntity.ok(ApiResponse.success("Ghim vị trí kiện hàng thành công", response));
    }

    @DeleteMapping("/{id}/pin/{placementId}")
    @Operation(summary = "Unpin placement", description = "Unpin a previously pinned placement in the load plan")
    public ResponseEntity<ApiResponse<PackagePlacementResponse>> unpinPlacement(
            @PathVariable("id") Long id,
            @PathVariable("placementId") Long placementId) {
        PackagePlacementResponse response = loadPlanService.unpinPlacement(id, placementId);
        return ResponseEntity.ok(ApiResponse.success("Bỏ ghim vị trí kiện hàng thành công", response));
    }

    @PostMapping("/{id}/rerun")
    @Operation(summary = "Rerun optimization", description = "Rerun optimization keeping pinned placements fixed, creating a new load plan version")
    public ResponseEntity<ApiResponse<LoadPlanResponse>> rerunOptimization(
            @PathVariable("id") Long id,
            @RequestBody @Valid RerunOptimizationRequest request) {
        LoadPlanResponse response = loadPlanService.rerunOptimization(id, request);
        return ResponseEntity.ok(ApiResponse.success("Chạy lại tối ưu hóa kế hoạch xếp hàng thành công", response));
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare plans", description = "Compare metrics side-by-side between two load plans")
    public ResponseEntity<ApiResponse<PlanComparisonResponse>> comparePlans(
            @RequestParam("planId1") Long planId1,
            @RequestParam("planId2") Long planId2) {
        PlanComparisonResponse response = loadPlanService.comparePlans(planId1, planId2);
        return ResponseEntity.ok(ApiResponse.success("So sánh kế hoạch xếp hàng thành công", response));
    }
}
