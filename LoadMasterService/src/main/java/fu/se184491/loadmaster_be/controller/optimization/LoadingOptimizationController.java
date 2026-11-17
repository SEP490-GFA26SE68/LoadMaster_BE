package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.TripLoadPlansResponse;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadingOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tối ưu xếp hàng 3D của một chuyến và đọc phương án.
 * Điều phối viên chạy tối ưu; quản lý công ty chỉ đọc. Mọi đường dẫn kiểm chuyến / job thuộc công ty của phiên.
 * Đơn vị: toạ độ và kích thước cm, khối lượng kg.
 */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class LoadingOptimizationController {

    private final fu.se184491.loadmaster_be.service.optimization.OptimizationAccess optimizationAccess;
    private final LoadingOptimizationService loadingOptimizationService;
    private final LoadPlanQueryService loadPlanQueryService;
    private final fu.se184491.loadmaster_be.service.optimization.OptimizationSubmissionService submission;

    @PostMapping("/{tripId}/optimize-loading")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> optimizeLoading(
            @PathVariable Long tripId,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            fu.se184491.loadmaster_be.dto.request.optimization.OptimizeLoadingRequest request
    ) {
        if (request != null && request.keepPinsFromPlanId() != null) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.OPTIMIZATION_RERUN_UNAVAILABLE);
        }
        var result = submission.submitInitial(tripId, key);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>(result.getData());
        // Preserve the team's camelCase reader alongside the reserved-job contract.
        data.put("jobId", data.get("job_id"));
        data.put("tripId", tripId);
        data.put("pinnedCount", 0);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Idempotency-Key", key)
                .body(ApiResponse.success("Optimization action reserved", data));
    }

    /** Trạng thái job: status, thời gian chạy và số phương án đã lưu có kiện ({@code packedPlanCount}). */
    @GetMapping("/optimization-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER') and @optimizationAccess.canReadJob(#jobId)")
    public ResponseEntity<ApiResponse<OptimizationJobStatusResponse>> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Trạng thái tác vụ tối ưu", loadingOptimizationService.getJobStatus(jobId)));
    }

    @GetMapping("/optimization-jobs/{jobId}/plans")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER') and @optimizationAccess.canReadJob(#jobId)")
    public ResponseEntity<ApiResponse<List<LoadPlanDetailResponse>>> getOptimizationJobPlans(
            @PathVariable String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Danh sách kế hoạch xếp hàng", loadPlanQueryService.getJobPlans(jobId)));
    }

    @GetMapping("/{tripId}/load-plans")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<TripLoadPlansResponse>> getTripLoadPlans(
            @PathVariable Long tripId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Danh sách kế hoạch xếp hàng của chuyến", loadPlanQueryService.getTripLoadPlans(tripId)));
    }
}