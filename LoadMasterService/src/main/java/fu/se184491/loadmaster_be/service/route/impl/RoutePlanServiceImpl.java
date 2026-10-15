package fu.se184491.loadmaster_be.service.route.impl;

import fu.se184491.loadmaster_be.dto.request.route.CreateRouteLegRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRoutePlanRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteSuggestionRequest;
import fu.se184491.loadmaster_be.dto.response.route.RouteLegResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteSuggestionResponse;
import fu.se184491.loadmaster_be.entity.route.RouteLeg;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.route.RouteSuggestion;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.route.RouteLegRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.route.RouteSuggestionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.route.RoutePlanService;
import fu.se184491.loadmaster_be.service.route.RoutePlanWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutePlanServiceImpl implements RoutePlanService {

    private final RoutePlanRepository routePlanRepository;
    private final RouteLegRepository routeLegRepository;
    private final RouteSuggestionRepository routeSuggestionRepository;
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final RoutePlanWriter routePlanWriter;

    @Override
    @Transactional
    public RoutePlanDetailResponse createRoutePlan(Long companyId, Long tripId, CreateRoutePlanRequest request) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        // Deactivate previous active plans for this trip
        routePlanRepository.deactivateAllByTripId(tripId);

        int version = request.getPlanVersion() != null && request.getPlanVersion() > 0
                ? request.getPlanVersion()
                : (routePlanRepository.findByTripId(tripId).size() + 1);

        RoutePlan routePlan = RoutePlan.builder()
                .trip(trip)
                .planVersion(version)
                .totalDistanceMeters(request.getTotalDistanceMeters())
                .totalDurationSeconds(request.getTotalDurationSeconds())
                .isActive(true)
                .build();

        RoutePlan savedPlan = routePlanRepository.save(routePlan);

        List<RouteLeg> legsToSave = new ArrayList<>();
        if (request.getLegs() != null) {
            for (CreateRouteLegRequest legReq : request.getLegs()) {
                DeliveryStop startStop = null;
                if (legReq.getStartStopId() != null) {
                    startStop = deliveryStopRepository.findById(legReq.getStartStopId()).orElse(null);
                }

                DeliveryStop endStop = deliveryStopRepository.findById(legReq.getEndStopId())
                        .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));

                RouteLeg leg = RouteLeg.builder()
                        .routePlan(savedPlan)
                        .legSequence(legReq.getLegSequence())
                        .startStop(startStop)
                        .endStop(endStop)
                        .distanceMeters(legReq.getDistanceMeters())
                        .durationSeconds(legReq.getDurationSeconds())
                        .polyline(legReq.getPolyline())
                        .build();

                legsToSave.add(leg);
            }
        }

        List<RouteLeg> savedLegs = routeLegRepository.saveAll(legsToSave);

        return mapToDetailResponse(savedPlan, savedLegs);
    }

    @Override
    @Transactional(readOnly = true)
    public RoutePlanDetailResponse getActiveRoutePlan(Long companyId, Long tripId) {
        RoutePlan plan = routePlanRepository.findByTripIdAndTripCompanyIdAndIsActiveTrue(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_PLAN_NOT_FOUND));

        List<RouteLeg> legs = routeLegRepository.findByRoutePlanIdOrderByLegSequenceAsc(plan.getId());
        return mapToDetailResponse(plan, legs);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoutePlanSummaryResponse> getRoutePlans(Long companyId, Long tripId, Pageable pageable) {
        return routePlanRepository.findByTripIdAndTripCompanyIdOrderByPlanVersionDesc(tripId, companyId, pageable)
                .map(this::mapToSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RouteLegResponse> getRouteLegs(Long companyId, Long routePlanId, Pageable pageable) {
        // Validate plan belongs to company
        routePlanRepository.findByIdAndTripCompanyId(routePlanId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_PLAN_NOT_FOUND));

        return routeLegRepository.findByRoutePlanIdAndRoutePlanTripCompanyIdOrderByLegSequenceAsc(routePlanId, companyId, pageable)
                .map(this::mapToLegResponse);
    }

    @Override
    @Transactional
    public RouteSuggestionResponse createSuggestion(Long companyId, Long legId, CreateRouteSuggestionRequest request) {
        RouteLeg leg = routeLegRepository.findByIdAndRoutePlanTripCompanyId(legId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_LEG_NOT_FOUND));

        RouteSuggestion suggestion = RouteSuggestion.builder()
                .routeLeg(leg)
                .tripExceptionId(request.getTripExceptionId())
                .suggestionType(request.getSuggestionType())
                .durationDiffSeconds(request.getDurationDiffSeconds())
                .distanceDiffMeters(request.getDistanceDiffMeters())
                .proposedPolyline(request.getProposedPolyline())
                .isAccepted(false)
                .build();

        return mapToSuggestionResponse(routeSuggestionRepository.save(suggestion));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RouteSuggestionResponse> getSuggestions(Long companyId, Long legId, Pageable pageable) {
        return routeSuggestionRepository.findByRouteLegIdAndRouteLegRoutePlanTripCompanyId(legId, companyId, pageable)
                .map(this::mapToSuggestionResponse);
    }

    @Override
    @Transactional
    public RouteSuggestionResponse acceptSuggestion(Long companyId, Long suggestionId) {
        RouteSuggestion suggestion = routeSuggestionRepository.findByIdAndRouteLegRoutePlanTripCompanyId(suggestionId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_SUGGESTION_NOT_FOUND));

        if (Boolean.TRUE.equals(suggestion.getIsAccepted())) {
            return mapToSuggestionResponse(suggestion);
        }

        RouteLeg targetLeg = suggestion.getRouteLeg();
        RoutePlan sourcePlan = targetLeg.getRoutePlan();
        Trip trip = sourcePlan.getTrip();

        List<RouteLeg> currentLegs = routeLegRepository.findByRoutePlanIdOrderByLegSequenceAsc(sourcePlan.getId());
        Integer newDistance = suggestion.getDistanceDiffMeters() != null
                ? targetLeg.getDistanceMeters() + suggestion.getDistanceDiffMeters()
                : targetLeg.getDistanceMeters();
        Integer newDuration = suggestion.getDurationDiffSeconds() != null
                ? targetLeg.getDurationSeconds() + suggestion.getDurationDiffSeconds()
                : targetLeg.getDurationSeconds();
        if (newDistance != null && newDistance < 0) {
            newDistance = 0;
        }
        if (newDuration != null && newDuration < 0) {
            newDuration = 0;
        }

        RoutePlan newPlan = routePlanWriter.cloneActiveWithLegOverride(
                trip,
                sourcePlan,
                currentLegs,
                targetLeg.getId(),
                newDistance,
                newDuration,
                suggestion.getProposedPolyline());

        syncTripRoutePlanCache(trip, newPlan);

        suggestion.setIsAccepted(true);
        return mapToSuggestionResponse(routeSuggestionRepository.save(suggestion));
    }

    private void syncTripRoutePlanCache(Trip trip, RoutePlan plan) {
        Map<String, Object> cache = trip.getRoutePlan() != null
                ? new HashMap<>(trip.getRoutePlan())
                : new HashMap<>();
        cache.put("activeRoutePlanId", plan.getId());
        cache.put("planVersion", plan.getPlanVersion());
        if (plan.getTotalDistanceMeters() != null) {
            cache.put("totalDistanceMeters", plan.getTotalDistanceMeters());
        }
        if (plan.getTotalDurationSeconds() != null) {
            cache.put("totalTravelDurationSeconds", plan.getTotalDurationSeconds());
        }
        trip.setRoutePlan(cache);
        tripRepository.save(trip);
    }

    // ── Mapping Helpers ────────────────────────────────────────────────────────

    private RoutePlanDetailResponse mapToDetailResponse(RoutePlan plan, List<RouteLeg> legs) {
        List<RouteLegResponse> legResponses = legs != null
                ? legs.stream().map(this::mapToLegResponse).toList()
                : Collections.emptyList();

        return RoutePlanDetailResponse.builder()
                .id(plan.getId())
                .tripId(plan.getTrip() != null ? plan.getTrip().getId() : null)
                .planVersion(plan.getPlanVersion())
                .totalDistanceMeters(plan.getTotalDistanceMeters())
                .totalDurationSeconds(plan.getTotalDurationSeconds())
                .isActive(plan.getIsActive())
                .createdAt(plan.getCreatedAt())
                .legs(legResponses)
                .build();
    }

    private RoutePlanSummaryResponse mapToSummaryResponse(RoutePlan plan) {
        return RoutePlanSummaryResponse.builder()
                .id(plan.getId())
                .tripId(plan.getTrip() != null ? plan.getTrip().getId() : null)
                .planVersion(plan.getPlanVersion())
                .totalDistanceMeters(plan.getTotalDistanceMeters())
                .totalDurationSeconds(plan.getTotalDurationSeconds())
                .isActive(plan.getIsActive())
                .createdAt(plan.getCreatedAt())
                .build();
    }

    private RouteLegResponse mapToLegResponse(RouteLeg leg) {
        return RouteLegResponse.builder()
                .id(leg.getId())
                .routePlanId(leg.getRoutePlan() != null ? leg.getRoutePlan().getId() : null)
                .legSequence(leg.getLegSequence())
                .startStopId(leg.getStartStop() != null ? leg.getStartStop().getId() : null)
                .endStopId(leg.getEndStop() != null ? leg.getEndStop().getId() : null)
                .distanceMeters(leg.getDistanceMeters())
                .durationSeconds(leg.getDurationSeconds())
                .polyline(leg.getPolyline())
                .build();
    }

    private RouteSuggestionResponse mapToSuggestionResponse(RouteSuggestion suggestion) {
        return RouteSuggestionResponse.builder()
                .id(suggestion.getId())
                .routeLegId(suggestion.getRouteLeg() != null ? suggestion.getRouteLeg().getId() : null)
                .tripExceptionId(suggestion.getTripExceptionId())
                .suggestionType(suggestion.getSuggestionType())
                .durationDiffSeconds(suggestion.getDurationDiffSeconds())
                .distanceDiffMeters(suggestion.getDistanceDiffMeters())
                .isAccepted(suggestion.getIsAccepted())
                .proposedPolyline(suggestion.getProposedPolyline())
                .createdAt(suggestion.getCreatedAt())
                .build();
    }
}
