package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.dto.request.planning.AddPackagesToTripRequest;
import fu.se184491.loadmaster_be.dto.request.planning.CreateTripRequest;
import fu.se184491.loadmaster_be.dto.response.planning.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;

public interface TripPlanningService {
    TripDetailResponse createTrip(Long companyId, CreateTripRequest request);

    TripDetailResponse addPackages(Long companyId, Long tripId, AddPackagesToTripRequest request);

    void removePackage(Long companyId, Long tripId, Long packageId);

    RouteOptimizationResult optimizeRoute(Long companyId, Long tripId);

    RouteOptimizationResult refreshEta(Long companyId, Long tripId);
}
