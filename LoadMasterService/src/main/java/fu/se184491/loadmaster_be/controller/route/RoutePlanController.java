package fu.se184491.loadmaster_be.controller.route;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.goong.PlaceAutocompleteResponse;
import fu.se184491.loadmaster_be.dto.goong.PlaceDetailResponse;
import fu.se184491.loadmaster_be.dto.request.route.CreateRoutePlanRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteSuggestionRequest;
import fu.se184491.loadmaster_be.dto.response.route.RouteLegResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteSuggestionResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.route.RoutePlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoutePlanController {

    private final RoutePlanService routePlanService;
    private final CurrentUserService currentUserService;
    private final TripLookupHelper tripLookupHelper;
    private final GoongMapsClient goongMapsClient;

    @PostMapping("/trips/{tripId}/route-plans")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<RoutePlanDetailResponse>> createRoutePlan(
            @PathVariable String tripId,
            @Valid @RequestBody CreateRoutePlanRequest request
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Long id = tripLookupHelper.requireTrip(tripId, companyId).getId();
        RoutePlanDetailResponse response = routePlanService.createRoutePlan(companyId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo kế hoạch lộ trình thành công", response));
    }

    @GetMapping("/trips/{tripId}/route-plans/active")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN', 'DRIVER')")
    public ResponseEntity<ApiResponse<RoutePlanDetailResponse>> getActiveRoutePlan(
            @PathVariable String tripId
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Long id = tripLookupHelper.requireTrip(tripId, companyId).getId();
        RoutePlanDetailResponse response = routePlanService.getActiveRoutePlan(companyId, id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/trips/{tripId}/route-plans")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public PageResponse<RoutePlanSummaryResponse> getRoutePlans(
            @PathVariable String tripId,
            Pageable pageable
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Long id = tripLookupHelper.requireTrip(tripId, companyId).getId();
        return PageResponse.of(routePlanService.getRoutePlans(companyId, id, pageable));
    }

    @GetMapping("/route-plans/{planId}/legs")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN', 'DRIVER')")
    public PageResponse<RouteLegResponse> getRouteLegs(
            @PathVariable Long planId,
            Pageable pageable
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        return PageResponse.of(routePlanService.getRouteLegs(companyId, planId, pageable));
    }

    @PostMapping("/route-legs/{legId}/suggestions")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'DRIVER')")
    public ResponseEntity<ApiResponse<RouteSuggestionResponse>> createSuggestion(
            @PathVariable Long legId,
            @Valid @RequestBody CreateRouteSuggestionRequest request
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        RouteSuggestionResponse response = routePlanService.createSuggestion(companyId, legId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo đề xuất đổi tuyến thành công", response));
    }

    @GetMapping("/route-legs/{legId}/suggestions")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public PageResponse<RouteSuggestionResponse> getSuggestions(
            @PathVariable Long legId,
            Pageable pageable
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        return PageResponse.of(routePlanService.getSuggestions(companyId, legId, pageable));
    }

    @PatchMapping("/route-suggestions/{id}/accept")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<RouteSuggestionResponse>> acceptSuggestion(
            @PathVariable Long id
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();
        RouteSuggestionResponse response = routePlanService.acceptSuggestion(companyId, id);
        return ResponseEntity.ok(ApiResponse.success("Chấp nhận đề xuất đổi tuyến thành công", response));
    }


    @GetMapping("/places/autocomplete")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<PlaceAutocompleteResponse>> autocompletePlaces(
            @RequestParam("input") String input
    ) {
        String keyword = input.trim();

        if (keyword.length() < 2 || keyword.length() > 200) {
            return ResponseEntity.badRequest().build();
        }

        PlaceAutocompleteResponse result =
                goongMapsClient.getPlaceAutocomplete(keyword);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/places/detail")
    @PreAuthorize("hasAnyAuthority('TRIP_MANAGE', 'ROLE_DISPATCHER', 'COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<PlaceDetailResponse>> getPlaceDetail(
            @RequestParam("placeId") String placeId
    ) {
        String id = placeId.trim();

        if (id.isEmpty() || id.length() > 255) {
            return ResponseEntity.badRequest().build();
        }

        PlaceDetailResponse result =
                goongMapsClient.getPlaceDetail(id);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

}
