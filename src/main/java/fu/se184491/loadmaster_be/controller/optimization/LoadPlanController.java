package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.service.optimize.LoadPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for LoadPlan management operations (S3-11).
 *
 * <p>API path: {@code /api/load-plans}
 *
 * <p>Approve flow:
 * <pre>
 *   POST /api/load-plans/{id}/approve
 *     → validates LIFO, sets approved = true, records AuditLog
 *     → returns 200 OK + updated LoadPlanResponse
 * </pre>
 */
@RestController
@RequestMapping("/api/load-plans")
@RequiredArgsConstructor
public class LoadPlanController {

    private final LoadPlanService loadPlanService;

    /**
     * Approve a load plan as the currently authenticated dispatcher.
     *
     * <p>Validates:
     * <ul>
     *   <li>Plan has at least one PackagePlacement.</li>
     *   <li>Placements satisfy LIFO ordering (stepSequence strictly ascending).</li>
     * </ul>
     *
     * @param planId         id of the LoadPlan to approve
     * @param authentication the authenticated principal (getName() = email set by Keycloak)
     * @return 200 OK with updated LoadPlanResponse (approved = true)
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('PLAN_APPROVE', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoadPlanResponse>> approvePlan(
            @PathVariable("id") Long planId,
            Authentication authentication) {

        String approverEmail = authentication.getName();
        LoadPlanResponse response = loadPlanService.approvePlan(planId, approverEmail);
        return ResponseEntity.ok(
                ApiResponse.success("Kế hoạch xếp hàng đã được duyệt", response));
    }
}
