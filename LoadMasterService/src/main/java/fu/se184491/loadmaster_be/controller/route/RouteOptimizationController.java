package fu.se184491.loadmaster_be.controller.route;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.route.RouteOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class RouteOptimizationController {

    private final RouteOptimizationService routeOptimizationService;
    private final CurrentUserService currentUserService;

    /**
     * Tối ưu tuyến. Chuyến Nháp thành Đã lập kế hoạch khi tối ưu xong.
     */
    @PostMapping("/{tripId}/optimize-route")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<RouteOptimizationResult> optimizeRoute(
            @PathVariable Long tripId
    ) {
        User currentUser = currentUserService.getCurrentUser();
        if (currentUser.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }

        RouteOptimizationResult result =
                routeOptimizationService.optimizeRoute(
                        currentUser.getCompany().getId(),
                        tripId,
                        currentUser.getId()
                );

        return ApiResponse.success(result);
    }
}
