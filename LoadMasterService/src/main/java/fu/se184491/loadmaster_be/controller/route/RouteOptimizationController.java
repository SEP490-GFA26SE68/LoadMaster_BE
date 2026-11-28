package fu.se184491.loadmaster_be.controller.route;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationRunResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.route.RouteOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RouteOptimizationController {

    private final RouteOptimizationService routeOptimizationService;
    private final CurrentUserService currentUserService;

    /**
     * Sinh ba phương án tuyến A · B · C. Chuyến vẫn Nháp cho đến khi duyệt một phương án.
     */
    @PostMapping("/trips/{tripId}/optimize-route")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<RouteOptimizationRunResponse> optimizeRoute(
            @PathVariable Long tripId
    ) {
        User currentUser = requireUser();
        RouteOptimizationRunResponse result = routeOptimizationService.optimizeRoute(
                currentUser.getCompany().getId(),
                tripId,
                currentUser.getId()
        );
        return ApiResponse.success("Đã có ba phương án tuyến", result);
    }

    /** Duyệt một phương án A · B · C: áp thứ tự điểm (LIFO packing theo thứ tự đó), chuyến Nháp thành Đã lập kế hoạch. */
    @PostMapping("/route-plans/{planId}/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<RouteOptimizationResult> approveRoutePlan(
            @PathVariable Long planId
    ) {
        User currentUser = requireUser();
        RouteOptimizationResult result = routeOptimizationService.approveRoutePlan(
                currentUser.getCompany().getId(),
                planId,
                currentUser.getId()
        );
        return ApiResponse.success("Đã duyệt phương án tuyến", result);
    }

    private User requireUser() {
        User currentUser = currentUserService.getCurrentUser();
        if (currentUser.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        return currentUser;
    }
}
