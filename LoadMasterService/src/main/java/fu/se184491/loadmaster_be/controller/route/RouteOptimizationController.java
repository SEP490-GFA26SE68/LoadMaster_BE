
package fu.se184491.loadmaster_be.controller.route;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
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

    @PostMapping("/{tripId}/optimize-route")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<RouteOptimizationResult> optimizeRoute(
            @PathVariable Long tripId
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        RouteOptimizationResult result =
                routeOptimizationService.optimizeRoute(
                        companyId,
                        tripId
                );

        return ApiResponse.success(result);
    }
}
