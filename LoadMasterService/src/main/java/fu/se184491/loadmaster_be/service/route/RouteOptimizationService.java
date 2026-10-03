package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.dto.route.StopSequenceRequest;
import fu.se184491.loadmaster_be.dto.route.StopSequenceResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.MissingCoordinatesException;
import fu.se184491.loadmaster_be.exception.RouteOptimizationUnavailableException;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RouteOptimizationService {

    private static final int SERVICE_TIME_SECONDS = 15 * 60;

    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final AuditLogRepository auditLogRepository;
    private final GoongMapsClient goongMapsClient;
    private final StopSequenceOptimizer stopSequenceOptimizer = new StopSequenceOptimizer();

    public RouteOptimizationService(
            TripRepository tripRepository,
            DeliveryStopRepository deliveryStopRepository,
            CargoPackageRepository cargoPackageRepository,
            AuditLogRepository auditLogRepository,
            GoongMapsClient goongMapsClient
    ) {
        this.tripRepository = tripRepository;
        this.deliveryStopRepository = deliveryStopRepository;
        this.cargoPackageRepository = cargoPackageRepository;
        this.auditLogRepository = auditLogRepository;
        this.goongMapsClient = goongMapsClient;
    }

    @Transactional
    public RouteOptimizationResult optimizeRoute(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
        stops.stream()
                .filter(stop -> stop.getLatitude() == null || stop.getLongitude() == null)
                .findFirst()
                .ifPresent(stop -> {
                    throw new MissingCoordinatesException(stop.getId());
                });
        Map<Long, List<CargoPackage>> packagesByStop = cargoPackageRepository
                .findByOrderDeliveryStopTripIdOrderByIdAsc(tripId).stream()
                .collect(Collectors.groupingBy(cargoPackage -> cargoPackage.getOrder().getDeliveryStop().getId()));

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
        StopSequenceResult sequence = stopSequenceOptimizer.optimize(
                new StopSequenceRequest(origin, optimizerStops, durationMatrix));

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
            DirectionsResponse.Route bestRoute = getDirections(segmentOrigin, coordinates(stop))
                    .getRoutes().stream()
                    .min(Comparator.comparingLong(DirectionsResponse.Route::getDurationSeconds))
                    .orElseThrow();
            int segmentSeconds = Math.toIntExact(bestRoute.getDurationSeconds());
            travelSeconds += segmentSeconds;
            distanceMeters += bestRoute.getDistanceMeters();
            elapsedSeconds += segmentSeconds + SERVICE_TIME_SECONDS;
            LocalDateTime plannedArrival = trip.getDepartureTime().plusSeconds(elapsedSeconds);
            stop.setPlannedArrival(plannedArrival);
            segments.add(new RouteOptimizationResult.RouteSegment(
                    previousStopId, stopId, segmentSeconds,
                    bestRoute.getDistanceMeters(), bestRoute.getPolyline()));
            schedules.add(new RouteOptimizationResult.StopSchedule(stopId, index + 1, plannedArrival));
            previousStopId = stopId;
            segmentOrigin = coordinates(stop);
        }

        updateSequences(stops, sequence.getOrderedStops(), stopsById);
        RouteOptimizationResult result = new RouteOptimizationResult(
                new RouteOptimizationResult.Origin(origin.latitude(), origin.longitude()),
                sequence.getOrderedStops(),
                travelSeconds,
                distanceMeters,
                elapsedSeconds,
                sequence.getDeadlineMissedStops(),
                segments,
                schedules);
        Map<String, Object> routePlan = routePlan(result);
        trip.setRoutePlan(routePlan);
        tripRepository.save(trip);
        auditLogRepository.save(AuditLog.builder()
                .actionType("ROUTE_OPTIMIZED")
                .entityName("Trip")
                .entityId(tripId.toString())
                .newValues(routePlan)
                .createdAt(LocalDateTime.now())
                .build());
        return result;
    }

    private DistanceMatrixResponse getDistanceMatrix(List<LatLng> points) {
        try {
            return goongMapsClient.getDistanceMatrix(points, points);
        } catch (ServiceUnavailableException exception) {
            throw new RouteOptimizationUnavailableException(exception);
        }
    }

    private DirectionsResponse getDirections(LatLng origin, LatLng destination) {
        try {
            return goongMapsClient.getDirections(origin, destination);
        } catch (ServiceUnavailableException exception) {
            throw new RouteOptimizationUnavailableException(exception);
        }
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
            return value;
        }).toList());
        plan.put("stopSchedules", result.getStopSchedules().stream().map(schedule -> Map.of(
                "stopId", schedule.stopId(),
                "stopSequence", schedule.stopSequence(),
                "plannedArrival", schedule.plannedArrival().toString())).toList());
        return plan;
    }

    private StopSequenceRequest.Stop optimizerStop(DeliveryStop stop, List<CargoPackage> packages) {
        LocalDateTime deadline = packages.stream()
                .map(CargoPackage::getOrder)
                .map(order -> order.getTimeWindowEnd())
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        return new StopSequenceRequest.Stop(
                stop.getId(),
                stop.getLatitude().doubleValue(),
                stop.getLongitude().doubleValue(),
                deadline,
                packages.size());
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
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("Cannot optimize a trip without an origin or delivery stops");
        }
        return coordinates(stops.getFirst());
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private LatLng coordinates(DeliveryStop stop) {
        return new LatLng(stop.getLatitude().doubleValue(), stop.getLongitude().doubleValue());
    }
}
