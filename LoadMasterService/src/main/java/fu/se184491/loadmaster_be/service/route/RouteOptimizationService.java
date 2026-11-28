package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationRunResponse;
import fu.se184491.loadmaster_be.dto.route.RoutePlanCandidateResponse;
import fu.se184491.loadmaster_be.dto.route.StopSequenceRequest;
import fu.se184491.loadmaster_be.dto.route.StopSequenceResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.MissingCoordinatesException;
import fu.se184491.loadmaster_be.exception.RouteOptimizationUnavailableException;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.planning.RequirementCargoPriority;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RouteOptimizationService {

    static final String OBJECTIVE_SHORTEST = "SHORTEST";
    static final String OBJECTIVE_AVOID_CONGESTION = "AVOID_CONGESTION";
    static final String OBJECTIVE_DEADLINE_FIRST = "DEADLINE_FIRST";

    private static final int SERVICE_TIME_SECONDS = 15 * 60;

    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final AuditLogRepository auditLogRepository;
    private final GoongMapsClient goongMapsClient;
    private final RoutePlanWriter routePlanWriter;
    private final RoutePlanRepository routePlanRepository;
    private final TripPackageResolver tripPackageResolver;
    private final TripStatusService tripStatusService;
    private final RequirementCargoPriority requirementCargoPriority;
    private final LoadPlanRepository loadPlanRepository;
    private final StopSequenceOptimizer stopSequenceOptimizer = new StopSequenceOptimizer();

    public RouteOptimizationService(
            TripRepository tripRepository,
            DeliveryStopRepository deliveryStopRepository,
            AuditLogRepository auditLogRepository,
            GoongMapsClient goongMapsClient,
            RoutePlanWriter routePlanWriter,
            RoutePlanRepository routePlanRepository,
            TripPackageResolver tripPackageResolver,
            TripStatusService tripStatusService,
            RequirementCargoPriority requirementCargoPriority,
            LoadPlanRepository loadPlanRepository
    ) {
        this.tripRepository = tripRepository;
        this.deliveryStopRepository = deliveryStopRepository;
        this.auditLogRepository = auditLogRepository;
        this.goongMapsClient = goongMapsClient;
        this.routePlanWriter = routePlanWriter;
        this.routePlanRepository = routePlanRepository;
        this.tripPackageResolver = tripPackageResolver;
        this.tripStatusService = tripStatusService;
        this.requirementCargoPriority = requirementCargoPriority;
        this.loadPlanRepository = loadPlanRepository;
    }

    @Transactional
    public RouteOptimizationRunResponse optimizeRoute(Long companyId, Long tripId) {
        return optimizeRoute(companyId, tripId, null);
    }

    /**
     * Sinh ba phương án tuyến, chưa đổi thứ tự điểm và chưa đưa chuyến sang Đã lập kế hoạch.
     * A/B cùng thứ tự (hạn gấp 30 phút rồi ưu tiên D-93), khác polyline; C được đổi thứ tự theo hạn.
     */
    @Transactional
    public RouteOptimizationRunResponse optimizeRoute(Long companyId, Long tripId, Long actorUserId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        tripStatusService.lockForTransition(trip.getId());

        if (trip.getStatus() != TripStatus.DRAFT && trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_NOT_EDITABLE);
        }
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdWithRequirementOrderByStopSequenceAsc(tripId);
        if (stops.isEmpty()) {
            throw new AppException(ErrorCode.TRIP_HAS_NO_STOPS);
        }
        stops.stream()
                .filter(stop -> stop.getLatitude() == null || stop.getLongitude() == null)
                .findFirst()
                .ifPresent(stop -> {
                    throw new MissingCoordinatesException(stop.getId());
                });

        Map<Long, List<CargoPackage>> packagesByStop = new HashMap<>();
        for (DeliveryStop stop : stops) {
            packagesByStop.put(stop.getId(), tripPackageResolver.packagesOfStop(stop.getId()));
        }

        LatLng origin = resolveOrigin(trip, stops);
        Map<Long, DeliveryStop> stopsById = stops.stream()
                .collect(Collectors.toMap(DeliveryStop::getId, Function.identity()));
        List<LatLng> points = new ArrayList<>();
        points.add(origin);
        points.addAll(stops.stream().map(this::coordinates).toList());

        DistanceMatrixResponse matrixResponse = getDistanceMatrix(points);
        int[][] durationMatrix = durationMatrix(matrixResponse);
        List<StopSequenceRequest.Stop> optimizerStops = stops.stream()
                .map(stop -> optimizerStop(stop, packagesByStop.getOrDefault(stop.getId(), List.of())))
                .toList();
        StopSequenceRequest sequenceRequest = new StopSequenceRequest(origin, optimizerStops, durationMatrix);

        StopSequenceResult abSequence = stopSequenceOptimizer.optimize(
                sequenceRequest, trip.getDepartureTime(), StopSequenceOptimizer.SequencePolicy.NEAREST_WITH_DEADLINE_RISK);
        StopSequenceResult cSequence = stopSequenceOptimizer.optimize(
                sequenceRequest, trip.getDepartureTime(), StopSequenceOptimizer.SequencePolicy.DEADLINE_FIRST);

        Map<String, DirectionsResponse> directionsCache = new HashMap<>();
        BuiltRoute planA = buildRoute(origin, abSequence, stopsById, trip, false, directionsCache);
        BuiltRoute planB = buildRoute(origin, abSequence, stopsById, trip, true, directionsCache);
        BuiltRoute planC = buildRoute(origin, cSequence, stopsById, trip, false, directionsCache);

        String runId = UUID.randomUUID().toString();
        RoutePlan savedA = persistCandidate(trip, runId, "A", OBJECTIVE_SHORTEST, planA);
        RoutePlan savedB = persistCandidate(trip, runId, "B", OBJECTIVE_AVOID_CONGESTION, planB);
        RoutePlan savedC = persistCandidate(trip, runId, "C", OBJECTIVE_DEADLINE_FIRST, planC);

        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("ROUTE_OPTIMIZED")
                .entityName("Trip")
                .entityId(tripId.toString())
                .newValues(Map.of(
                        "runId", runId,
                        "planIds", List.of(savedA.getId(), savedB.getId(), savedC.getId()),
                        "actorUserId", actorUserId == null ? "" : actorUserId.toString()))
                .createdAt(Instant.now())
                .build());

        RouteOptimizationResult.Origin originDto = new RouteOptimizationResult.Origin(origin.latitude(), origin.longitude());
        return new RouteOptimizationRunResponse(runId, originDto, List.of(
                toCandidate(savedA, planA, true),
                toCandidate(savedB, planB, true),
                toCandidate(savedC, planC, planC.result().getOrderedStops().equals(planA.result().getOrderedStops()))
        ));
    }

    @Transactional
    public RouteOptimizationResult approveRoutePlan(Long companyId, Long planId, Long actorUserId) {
        RoutePlan plan = routePlanRepository.findByIdAndTripCompanyId(planId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_PLAN_NOT_FOUND));
        if (plan.getCandidateLabel() == null || plan.getProposalJson() == null) {
            throw new AppException(ErrorCode.ROUTE_PLAN_NOT_CANDIDATE);
        }
        if (Boolean.TRUE.equals(plan.getApproved()) && Boolean.TRUE.equals(plan.getIsActive())) {
            throw new AppException(ErrorCode.ROUTE_PLAN_ALREADY_APPROVED);
        }
        Trip trip = plan.getTrip();
        tripStatusService.lockForTransition(trip.getId());
        if (trip.getStatus() != TripStatus.DRAFT && trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_NOT_EDITABLE);
        }

        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdWithRequirementOrderByStopSequenceAsc(trip.getId());
        Map<Long, DeliveryStop> stopsById = stops.stream()
                .collect(Collectors.toMap(DeliveryStop::getId, Function.identity()));
        RouteOptimizationResult result = resultFromProposal(plan.getProposalJson());
        updateSequences(stops, result.getOrderedStops(), stopsById);
        for (RouteOptimizationResult.StopSchedule schedule : result.getStopSchedules()) {
            DeliveryStop stop = stopsById.get(schedule.stopId());
            if (stop != null) {
                stop.setPlannedArrival(schedule.plannedArrival());
            }
        }

        routePlanRepository.deactivateAllByTripId(trip.getId());
        for (RoutePlan other : routePlanRepository.findByTripId(trip.getId())) {
            if (Boolean.TRUE.equals(other.getApproved()) && !other.getId().equals(plan.getId())) {
                other.setApproved(false);
            }
        }
        plan.setIsActive(true);
        plan.setApproved(true);
        routePlanRepository.save(plan);

        Map<String, Object> routePlanCache = routePlan(result);
        routePlanCache.put("activeRoutePlanId", plan.getId());
        routePlanCache.put("planVersion", plan.getPlanVersion());
        routePlanCache.put("runId", plan.getRunId());
        routePlanCache.put("candidateLabel", plan.getCandidateLabel());
        trip.setRoutePlan(routePlanCache);
        tripRepository.save(trip);

        requirementCargoPriority.applyStops(stops);
        loadPlanRepository.markStaleByTripId(trip.getId());

        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("ROUTE_PLAN_APPROVED")
                .entityName("RoutePlan")
                .entityId(plan.getId().toString())
                .newValues(routeAuditSummary(
                        result.getStopSchedules().size(),
                        result.getTotalDistanceMeters(),
                        result.getEstimatedTotalDurationSeconds(),
                        result.getDeadlineMissedStops(),
                        plan.getId(),
                        plan.getPlanVersion()))
                .createdAt(Instant.now())
                .build());

        if (trip.getStatus() == TripStatus.DRAFT) {
            tripStatusService.transitionTo(trip.getId(), TripStatus.PLANNED, actorUserId);
        }
        return result;
    }

    static Map<String, Object> routeAuditSummary(int stopCount, long distanceMeters, int elapsedSeconds,
                                                 List<Long> deadlineMissedStops,
                                                 Long routePlanId, Integer planVersion) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stopCount", stopCount);
        summary.put("distanceKm", Math.round(distanceMeters / 100.0) / 10.0);
        summary.put("durationMinutes", Math.round(elapsedSeconds / 60.0));
        summary.put("deadlineMissedCount", deadlineMissedStops == null ? 0 : deadlineMissedStops.size());
        summary.put("routePlanId", routePlanId);
        summary.put("planVersion", planVersion);
        return summary;
    }

    private RoutePlan persistCandidate(Trip trip, String runId, String label, String objective, BuiltRoute built) {
        List<RoutePlanWriter.LegDraft> legDrafts = new ArrayList<>();
        Map<Long, DeliveryStop> stopsById = built.stopsById();
        List<RouteOptimizationResult.RouteSegment> segments = built.result().getSegments();
        for (int index = 0; index < segments.size(); index++) {
            RouteOptimizationResult.RouteSegment segment = segments.get(index);
            DeliveryStop startStop = segment.fromStopId() != null ? stopsById.get(segment.fromStopId()) : null;
            DeliveryStop endStop = stopsById.get(segment.toStopId());
            legDrafts.add(new RoutePlanWriter.LegDraft(
                    index + 1,
                    startStop,
                    endStop,
                    Math.toIntExact(segment.distanceMeters()),
                    Math.toIntExact(segment.durationSeconds()),
                    segment.polyline(),
                    segment.steps()));
        }
        return routePlanWriter.persistCandidate(
                trip,
                Math.toIntExact(built.result().getTotalDistanceMeters()),
                built.result().getTotalTravelDurationSeconds(),
                legDrafts,
                new RoutePlanWriter.CandidateMeta(runId, label, objective, proposalOf(built.result())));
    }

    private RoutePlanCandidateResponse toCandidate(RoutePlan plan, BuiltRoute built, boolean sameStopOrderAsA) {
        return new RoutePlanCandidateResponse(
                plan.getId(),
                plan.getCandidateLabel(),
                plan.getObjective(),
                sameStopOrderAsA,
                built.result());
    }

    private BuiltRoute buildRoute(
            LatLng origin,
            StopSequenceResult sequence,
            Map<Long, DeliveryStop> stopsById,
            Trip trip,
            boolean avoidCongestion,
            Map<String, DirectionsResponse> directionsCache
    ) {
        List<RouteOptimizationResult.RouteSegment> segments = new ArrayList<>();
        List<RouteOptimizationResult.StopSchedule> schedules = new ArrayList<>();
        int travelSeconds = 0;
        long distanceMeters = 0;
        int elapsedSeconds = 0;
        LatLng segmentOrigin = origin;
        Long previousStopId = null;
        for (int index = 0; index < sequence.getOrderedStops().size(); index++) {
            Long stopId = sequence.getOrderedStops().get(index);
            DeliveryStop stop = stopsById.get(stopId);
            DirectionsResponse directions = cachedDirections(segmentOrigin, coordinates(stop), directionsCache);
            DirectionsResponse.Route shortest = RoutePathSelector.shortest(directions);
            DirectionsResponse.Route chosen = avoidCongestion
                    ? RoutePathSelector.quieter(directions, shortest, trip.getDepartureTime())
                    : shortest;
            int segmentSeconds = Math.toIntExact(chosen.getDurationSeconds());
            travelSeconds += segmentSeconds;
            distanceMeters += chosen.getDistanceMeters();
            elapsedSeconds += segmentSeconds;
            LocalDateTime plannedArrival = trip.getDepartureTime().plusSeconds(elapsedSeconds);
            elapsedSeconds += SERVICE_TIME_SECONDS;
            segments.add(new RouteOptimizationResult.RouteSegment(
                    previousStopId, stopId, segmentSeconds,
                    chosen.getDistanceMeters(), chosen.getPolyline(),
                    chosen.toGuidanceSteps()));
            schedules.add(new RouteOptimizationResult.StopSchedule(stopId, index + 1, plannedArrival));
            previousStopId = stopId;
            segmentOrigin = coordinates(stop);
        }
        RouteOptimizationResult result = new RouteOptimizationResult(
                new RouteOptimizationResult.Origin(origin.latitude(), origin.longitude()),
                sequence.getOrderedStops(),
                travelSeconds,
                distanceMeters,
                elapsedSeconds,
                sequence.getDeadlineMissedStops(),
                segments,
                schedules);
        return new BuiltRoute(result, stopsById);
    }

    private DirectionsResponse cachedDirections(
            LatLng origin,
            LatLng destination,
            Map<String, DirectionsResponse> cache
    ) {
        String key = origin.latitude() + "," + origin.longitude() + ">" + destination.latitude() + "," + destination.longitude();
        return cache.computeIfAbsent(key, ignored -> getDirections(origin, destination, true));
    }

    private DistanceMatrixResponse getDistanceMatrix(List<LatLng> points) {
        try {
            return goongMapsClient.getDistanceMatrix(points, points);
        } catch (ServiceUnavailableException exception) {
            throw new RouteOptimizationUnavailableException(exception);
        }
    }

    private DirectionsResponse getDirections(LatLng origin, LatLng destination, boolean alternatives) {
        try {
            return goongMapsClient.getDirections(origin, destination, alternatives);
        } catch (ServiceUnavailableException exception) {
            throw new RouteOptimizationUnavailableException(exception);
        }
    }

    private Map<String, Object> proposalOf(RouteOptimizationResult result) {
        return routePlan(result);
    }

    private Map<String, Object> routePlan(RouteOptimizationResult result) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("origin", Map.of(
                "latitude", result.getOrigin().latitude(),
                "longitude", result.getOrigin().longitude()));
        plan.put("orderedStops", result.getOrderedStops());
        plan.put("totalTravelDurationSeconds", result.getTotalTravelDurationSeconds());
        plan.put("totalDistanceMeters", result.getTotalDistanceMeters());
        plan.put("estimatedTotalDurationSeconds", result.getEstimatedTotalDurationSeconds());
        plan.put("deadlineMissedStops", result.getDeadlineMissedStops());
        plan.put("segments", result.getSegments().stream().map(segment -> {
            Map<String, Object> value = new HashMap<>();
            value.put("fromStopId", segment.fromStopId());
            value.put("toStopId", segment.toStopId());
            value.put("durationSeconds", segment.durationSeconds());
            value.put("distanceMeters", segment.distanceMeters());
            value.put("polyline", segment.polyline());
            value.put("steps", segment.steps());
            return value;
        }).toList());
        plan.put("stopSchedules", result.getStopSchedules().stream().map(schedule -> Map.of(
                "stopId", schedule.stopId(),
                "stopSequence", schedule.stopSequence(),
                "plannedArrival", schedule.plannedArrival().toString())).toList());
        return plan;
    }

    @SuppressWarnings("unchecked")
    static RouteOptimizationResult resultFromProposal(Map<String, Object> proposal) {
        Map<String, Object> originMap = (Map<String, Object>) proposal.get("origin");
        RouteOptimizationResult.Origin origin = new RouteOptimizationResult.Origin(
                ((Number) originMap.get("latitude")).doubleValue(),
                ((Number) originMap.get("longitude")).doubleValue());
        List<Long> orderedStops = numbers(proposal.get("orderedStops"));
        List<Long> missed = numbers(proposal.get("deadlineMissedStops"));
        List<Map<String, Object>> segmentMaps = (List<Map<String, Object>>) proposal.get("segments");
        List<RouteOptimizationResult.RouteSegment> segments = new ArrayList<>();
        if (segmentMaps != null) {
            for (Map<String, Object> segment : segmentMaps) {
                segments.add(new RouteOptimizationResult.RouteSegment(
                        segment.get("fromStopId") == null ? null : ((Number) segment.get("fromStopId")).longValue(),
                        ((Number) segment.get("toStopId")).longValue(),
                        ((Number) segment.get("durationSeconds")).longValue(),
                        ((Number) segment.get("distanceMeters")).longValue(),
                        (String) segment.get("polyline"),
                        segment.get("steps") instanceof List<?> steps
                                ? (List<Map<String, Object>>) steps
                                : List.of()));
            }
        }
        List<Map<String, Object>> scheduleMaps = (List<Map<String, Object>>) proposal.get("stopSchedules");
        List<RouteOptimizationResult.StopSchedule> schedules = new ArrayList<>();
        if (scheduleMaps != null) {
            for (Map<String, Object> schedule : scheduleMaps) {
                schedules.add(new RouteOptimizationResult.StopSchedule(
                        ((Number) schedule.get("stopId")).longValue(),
                        ((Number) schedule.get("stopSequence")).intValue(),
                        LocalDateTime.parse(String.valueOf(schedule.get("plannedArrival")))));
            }
        }
        return new RouteOptimizationResult(
                origin,
                orderedStops,
                ((Number) proposal.get("totalTravelDurationSeconds")).intValue(),
                ((Number) proposal.get("totalDistanceMeters")).longValue(),
                ((Number) proposal.get("estimatedTotalDurationSeconds")).intValue(),
                missed,
                segments,
                schedules);
    }

    private static List<Long> numbers(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return ids;
    }

    private StopSequenceRequest.Stop optimizerStop(DeliveryStop stop, List<CargoPackage> packages) {
        LocalDateTime deadline = null;
        int priority = DeliveryRequirementPriority.NORMAL.packingRank();
        DeliveryRequirement requirement = stop.getDeliveryRequirement();
        if (requirement != null) {
            deadline = requirement.getDeadline();
            if (requirement.getPriority() != null) {
                priority = requirement.getPriority().packingRank();
            }
        }
        return new StopSequenceRequest.Stop(
                stop.getId(),
                stop.getLatitude().doubleValue(),
                stop.getLongitude().doubleValue(),
                deadline,
                packages.size(),
                priority
        );
    }

    private int[][] durationMatrix(DistanceMatrixResponse response) {
        List<DistanceMatrixResponse.Row> rows = response.getRows();
        int[][] matrix = new int[rows.size()][];
        for (int row = 0; row < rows.size(); row++) {
            matrix[row] = rows.get(row).getElements().stream()
                    .mapToInt(element -> Math.toIntExact(element.getDuration().getValue()))
                    .toArray();
        }
        return matrix;
    }

    private void updateSequences(
            List<DeliveryStop> stops,
            List<Long> orderedStopIds,
            Map<Long, DeliveryStop> stopsById
    ) {
        int temporarySequence = stops.stream()
                .map(DeliveryStop::getStopSequence)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        for (int index = 0; index < orderedStopIds.size(); index++) {
            stopsById.get(orderedStopIds.get(index)).setStopSequence(temporarySequence + index);
        }
        deliveryStopRepository.flush();
        for (int index = 0; index < orderedStopIds.size(); index++) {
            stopsById.get(orderedStopIds.get(index)).setStopSequence(index + 1);
        }
        deliveryStopRepository.flush();
    }

    private LatLng resolveOrigin(Trip trip, List<DeliveryStop> stops) {
        if (trip.getOriginLatitude() != null && trip.getOriginLongitude() != null) {
            return new LatLng(
                    trip.getOriginLatitude().doubleValue(),
                    trip.getOriginLongitude().doubleValue()
            );
        }
        Map<String, Object> routePlan = trip.getRoutePlan();
        if (routePlan != null) {
            Double latitude = number(routePlan.get("originLat"));
            Double longitude = number(routePlan.get("originLng"));
            if (latitude != null && longitude != null) {
                return new LatLng(latitude, longitude);
            }
            Object storedOrigin = routePlan.get("origin");
            if (storedOrigin instanceof Map<?, ?> originMap) {
                latitude = number(originMap.get("latitude"));
                longitude = number(originMap.get("longitude"));
                if (latitude != null && longitude != null) {
                    return new LatLng(latitude, longitude);
                }
            }
        }
        throw new AppException(ErrorCode.INVALID_INPUT);
    }

    private Double number(Object value) {
        return value instanceof Number numberValue ? numberValue.doubleValue() : null;
    }

    private LatLng coordinates(DeliveryStop stop) {
        return new LatLng(stop.getLatitude().doubleValue(), stop.getLongitude().doubleValue());
    }

    private record BuiltRoute(RouteOptimizationResult result, Map<Long, DeliveryStop> stopsById) {
    }
}
