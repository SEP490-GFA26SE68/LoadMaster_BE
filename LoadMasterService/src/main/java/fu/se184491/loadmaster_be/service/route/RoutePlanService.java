package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.request.route.CreateRoutePlanRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteSuggestionRequest;
import fu.se184491.loadmaster_be.dto.response.route.RouteLegResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteSuggestionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RoutePlanService {

    RoutePlanDetailResponse createRoutePlan(Long companyId, Long tripId, CreateRoutePlanRequest request);

    RoutePlanDetailResponse getActiveRoutePlan(Long companyId, Long tripId);

    Page<RoutePlanSummaryResponse> getRoutePlans(Long companyId, Long tripId, Pageable pageable);

    Page<RouteLegResponse> getRouteLegs(Long companyId, Long routePlanId, Pageable pageable);

    RouteSuggestionResponse createSuggestion(Long companyId, Long legId, CreateRouteSuggestionRequest request);

    Page<RouteSuggestionResponse> getSuggestions(Long companyId, Long legId, Pageable pageable);

    RouteSuggestionResponse acceptSuggestion(Long companyId, Long suggestionId);
}
