package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.optimization.OptimizationJobRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobResponse;
import fu.se184491.loadmaster_be.service.optimize.AsyncOptimizationRunner;
import fu.se184491.loadmaster_be.service.optimize.LoadPlanService;
import fu.se184491.loadmaster_be.service.optimize.OptimizationJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Spring Boot REST endpoints consumed by the React frontend.
 *
 * Architecture note (S3-04):
 *   React → (user token)  → POST /api/optimization/jobs  (this controller)
 *                        → Spring Boot calls FastAPI (service token, see S3-06)
 *   API path matrix:
 *     /api/optimization/jobs        — Spring Boot (frontend calls)
 *     /api/v1/optimization/jobs     — FastAPI engine (Spring Boot calls internally)
 */
@RestController
@RequestMapping("/api/optimization/jobs")
@RequiredArgsConstructor
public class OptimizationController {

    private final OptimizationJobService optimizationJobService;
    private final LoadPlanService loadPlanService;
    private final AsyncOptimizationRunner asyncOptimizationRunner;

    // ── POST /api/optimization/jobs ───────────────────────────────────────────

    /**
     * Submit a new optimization job for a trip.
     * Returns 202 Accepted immediately; client should poll GET /{id} for status.
     *
     * @param request body containing tripId, objective, timeLimitSec, seed
     * @return 202 with the created job (PENDING status + jobUuid)
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('OPTIMIZATION_MANAGE', 'ADMIN')")
    public ResponseEntity<ApiResponse<OptimizationJobResponse>> createJob(
            @RequestBody @Valid OptimizationJobRequest request) {
        OptimizationJobResponse response = optimizationJobService.createJob(request);
        // Fire-and-forget: engine call runs in background thread pool (S3-08)
        asyncOptimizationRunner.runJob(response.getJobUuid());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Tác vụ tối ưu đã được khởi tạo", response));
    }

    // ── GET /api/optimization/jobs/{jobUuid} ──────────────────────────────────

    /**
     * Poll the status and metadata of a specific optimization job.
     *
     * @param jobUuid UUID of the job (from createJob response)
     * @return 200 with job status, computationMs, objective, etc.
     */
    @GetMapping("/{jobUuid}")
    @PreAuthorize("hasAnyAuthority('OPTIMIZATION_MANAGE', 'ADMIN')")
    public ResponseEntity<ApiResponse<OptimizationJobResponse>> getJob(
            @PathVariable String jobUuid) {
        OptimizationJobResponse response = optimizationJobService.getJob(jobUuid);
        return ResponseEntity.ok(ApiResponse.success("Thông tin tác vụ tối ưu", response));
    }

    // ── GET /api/optimization/jobs/{jobUuid}/plans ────────────────────────────

    /**
     * Retrieve all LoadPlans (with package placements) produced by this job.
     * Used by the React 3D visualisation component.
     *
     * @param jobUuid UUID of the completed job
     * @return 200 with list of LoadPlanResponse (each with nested placements)
     */
    @GetMapping("/{jobUuid}/plans")
    @PreAuthorize("hasAnyAuthority('OPTIMIZATION_MANAGE', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<LoadPlanResponse>>> getPlans(
            @PathVariable String jobUuid) {
        List<LoadPlanResponse> plans = loadPlanService.getPlansForJob(jobUuid);
        return ResponseEntity.ok(ApiResponse.success("Danh sách kế hoạch xếp hàng", plans));
    }
}
