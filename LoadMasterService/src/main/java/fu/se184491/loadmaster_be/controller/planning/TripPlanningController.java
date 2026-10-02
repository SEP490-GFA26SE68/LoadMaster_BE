package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.dto.request.planning.AddPackagesToTripRequest;
import fu.se184491.loadmaster_be.dto.request.planning.CreateTripRequest;
import fu.se184491.loadmaster_be.dto.response.planning.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.planning.TripPlanningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@PreAuthorize("hasAuthority('TRIP_MANAGE')")
@RequiredArgsConstructor
public class TripPlanningController {

    private final TripPlanningService tripPlanningService;
    private final CurrentUserService currentUserService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripDetailResponse createTrip(@Valid @RequestBody CreateTripRequest request) {
        return tripPlanningService.createTrip(currentUserService.getCurrentCompanyId(), request);
    }

    @PostMapping("/{id}/packages")
    public TripDetailResponse addPackages(
            @PathVariable Long id,
            @Valid @RequestBody AddPackagesToTripRequest request
    ) {
        return tripPlanningService.addPackages(currentUserService.getCurrentCompanyId(), id, request);
    }

    @DeleteMapping("/{id}/packages/{packageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePackage(@PathVariable Long id, @PathVariable Long packageId) {
        tripPlanningService.removePackage(currentUserService.getCurrentCompanyId(), id, packageId);
    }

    @PostMapping("/{id}/optimize-route")
    public RouteOptimizationResult optimizeRoute(@PathVariable Long id) {
        return tripPlanningService.optimizeRoute(currentUserService.getCurrentCompanyId(), id);
    }

    @GetMapping("/{id}/eta")
    public RouteOptimizationResult refreshEta(@PathVariable Long id) {
        return tripPlanningService.refreshEta(currentUserService.getCurrentCompanyId(), id);
    }
}
