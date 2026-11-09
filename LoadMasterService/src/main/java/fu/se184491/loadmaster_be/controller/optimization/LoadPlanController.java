package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.optimization.ApproveEditsRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanComparisonResponse;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanService;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualPlanApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phương án xếp hàng: đọc, duyệt, ghim, chạy lại, so sánh.
 * Ghi (ghim, chạy lại, duyệt) chỉ điều phối viên; đọc thêm quản lý công ty.
 * Mọi đường dẫn kiểm phương án thuộc công ty của phiên (công ty khác: LOAD_PLAN_NOT_FOUND).
 */
@RestController
@RequestMapping("/api/load-plans")
@RequiredArgsConstructor
@Tag(name = "Load Plan", description = "APIs for managing load plans, placements, and pinning")
public class LoadPlanController {

    private final LoadPlanService loadPlanService;
    private final LoadPlanQueryService loadPlanQueryService;
    private final ManualPlanApprovalService manualPlanApprovalService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    @Operation(summary = "Get load plan", description = "Plan with placements (cm, kg) and unplaced packages")
    public ResponseEntity<ApiResponse<LoadPlanDetailResponse>> getPlan(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Kế hoạch xếp hàng", loadPlanQueryService.getPlan(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Approve load plan", description = "Approve the plan; trip status is not changed")
    public ResponseEntity<ApiResponse<LoadPlanDetailResponse>> approvePlan(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "Kế hoạch xếp hàng đã được phê duyệt thành công", loadPlanQueryService.approvePlan(id)));
    }

    @PostMapping("/{id}/approve-edits")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Approve manual edits",
            description = "Validate moved / rotated placements (cm) and save them as a new approved, manually edited plan")
    public ResponseEntity<ApiResponse<LoadPlanDetailResponse>> approveEdits(
            @PathVariable("id") Long id,
            @RequestBody @Valid ApproveEditsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Đã duyệt bản chỉnh tay", manualPlanApprovalService.approveEdits(id, request)));
    }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Pin placement", description = "Pin a placement in the load plan to fix its position for subsequent re-runs")
    public ResponseEntity<ApiResponse<PackagePlacementResponse>> pinPlacement(
            @PathVariable("id") Long id,
            @RequestBody @Valid PinPlacementRequest request) {
        loadPlanQueryService.requireOwnedPlan(id);
        PackagePlacementResponse response = loadPlanService.pinPlacement(id, request);
        return ResponseEntity.ok(ApiResponse.success("Ghim vị trí kiện hàng thành công", response));
    }

    @DeleteMapping("/{id}/pin/{placementId}")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Unpin placement", description = "Unpin a previously pinned placement in the load plan")
    public ResponseEntity<ApiResponse<PackagePlacementResponse>> unpinPlacement(
            @PathVariable("id") Long id,
            @PathVariable("placementId") Long placementId) {
        loadPlanQueryService.requireOwnedPlan(id);
        PackagePlacementResponse response = loadPlanService.unpinPlacement(id, placementId);
        return ResponseEntity.ok(ApiResponse.success("Bỏ ghim vị trí kiện hàng thành công", response));
    }

    @PostMapping("/{id}/rerun")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Rerun optimization", description = "Rerun optimization keeping pinned placements fixed, creating a new load plan version")
    public ResponseEntity<ApiResponse<LoadPlanResponse>> rerunOptimization(
            @PathVariable("id") Long id,
            @RequestBody @Valid RerunOptimizationRequest request) {
        loadPlanQueryService.requireOwnedPlan(id);
        LoadPlanResponse response = loadPlanService.rerunOptimization(id, request);
        return ResponseEntity.ok(ApiResponse.success("Chạy lại tối ưu hóa kế hoạch xếp hàng thành công", response));
    }

    @GetMapping("/compare")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    @Operation(summary = "Compare plans", description = "Compare metrics side-by-side between two load plans")
    public ResponseEntity<ApiResponse<PlanComparisonResponse>> comparePlans(
            @RequestParam("planId1") Long planId1,
            @RequestParam("planId2") Long planId2) {
        loadPlanQueryService.requireOwnedPlan(planId1);
        loadPlanQueryService.requireOwnedPlan(planId2);
        PlanComparisonResponse response = loadPlanService.comparePlans(planId1, planId2);
        return ResponseEntity.ok(ApiResponse.success("So sánh kế hoạch xếp hàng thành công", response));
    }
}
