package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.monitoring.RerouteRouteType;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.dto.response.monitoring.RerouteOptionResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.RerouteProposalResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripRerouteResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.monitoring.TripReroute;
import fu.se184491.loadmaster_be.entity.route.RouteLeg;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.route.RouteSuggestion;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripRerouteRepository;
import fu.se184491.loadmaster_be.repository.route.RouteLegRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.route.RouteSuggestionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.RerouteService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.route.RoutePlanWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RerouteServiceImpl implements RerouteService {

    private static final double FALLBACK_SPEED_KMH = 30.0;
    private static final double HIGHWAY_MIN_KM = 15.0;

    private final TripLookupHelper tripLookupHelper;
    private final GpsTrackingRepository gpsTrackingRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final TripRerouteRepository tripRerouteRepository;
    private final GoongMapsClient goongMapsClient;
    private final UserRepository userRepository;
    private final RoutePlanRepository routePlanRepository;
    private final RouteLegRepository routeLegRepository;
    private final RouteSuggestionRepository routeSuggestionRepository;
    private final RoutePlanWriter routePlanWriter;
    private final TripRepository tripRepository;

    @Override
    @Transactional
    public RerouteProposalResponse propose(String tripIdOrCode, Long companyId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }

        GpsTracking gps = gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.NO_GPS_LOCATION));

        DeliveryStop nextStop = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId()).stream()
                .filter(stop -> EnumSet.of(DeliveryStopStatus.PENDING, DeliveryStopStatus.ARRIVED).contains(stop.getStatus()))
                .filter(stop -> stop.getLatitude() != null && stop.getLongitude() != null)
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));

        LatLng from = new LatLng(gps.getLatitude().doubleValue(), gps.getLongitude().doubleValue());
        LatLng to = new LatLng(nextStop.getLatitude().doubleValue(), nextStop.getLongitude().doubleValue());
        LocalDateTime requestedAt = MonitoringTime.now();

        LegMetrics baseline = resolveBaseline(from, to);
        List<RerouteOptionResponse> options = buildOptions(baseline, requestedAt);

        RerouteProposalResponse proposal = RerouteProposalResponse.builder()
                .tripId(trip.getTripCode())
                .requestedAt(MonitoringTime.toIso(requestedAt))
                .stopId(String.valueOf(nextStop.getId()))
                .stopNumber(nextStop.getStopSequence())
                .options(options)
                .isMockResult(false)
                .build();

        tripRerouteRepository.save(TripReroute.builder()
                .trip(trip)
                .stopId(nextStop.getId())
                .stopNumber(nextStop.getStopSequence())
                .proposalJson(toProposalMap(proposal))
                .requestedAt(requestedAt)
                .build());

        return proposal;
    }

    @Override
    @Transactional
    public TripRerouteResponse confirm(String tripIdOrCode, int routeIndex, Long companyId, Long confirmerUserId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        TripReroute latest = tripRerouteRepository.findFirstByTripIdOrderByRequestedAtDesc(trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REROUTE_INDEX));

        if (latest.getConfirmedAt() != null) {
            throw new AppException(ErrorCode.INVALID_REROUTE_INDEX);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> optionMaps = (List<Map<String, Object>>) latest.getProposalJson().get("options");
        if (optionMaps == null || routeIndex < 0 || routeIndex >= optionMaps.size()) {
            throw new AppException(ErrorCode.INVALID_REROUTE_INDEX);
        }

        Map<String, Object> selected = optionMaps.get(routeIndex);
        User confirmer = confirmerUserId != null
                ? userRepository.findById(confirmerUserId).orElse(null)
                : null;
        LocalDateTime confirmedAt = MonitoringTime.now();

        TripRerouteResponse response = TripRerouteResponse.builder()
                .index(routeIndex)
                .route(stringVal(selected.get("route")))
                .distanceKm(doubleVal(selected.get("distanceKm")))
                .durationMinutes(intVal(selected.get("durationMinutes")))
                .extraMs(longVal(selected.get("extraMs")))
                .eta(stringVal(selected.get("eta")))
                .stopNumber(latest.getStopNumber())
                .confirmedAt(MonitoringTime.toIso(confirmedAt))
                .confirmedBy(confirmer != null ? String.valueOf(confirmer.getId()) : null)
                .build();

        latest.setSelectedIndex(routeIndex);
        latest.setConfirmedAt(confirmedAt);
        latest.setConfirmedBy(confirmer);
        latest.setConfirmedJson(toConfirmedMap(response));
        tripRerouteRepository.save(latest);

        applyConfirmedRerouteToRoutePlan(trip, latest, selected);

        return response;
    }

    /**
     * Bridges monitoring TripReroute into canonical RouteSuggestion + new RoutePlan version.
     */
    private void applyConfirmedRerouteToRoutePlan(Trip trip, TripReroute reroute, Map<String, Object> selected) {
        Optional<RoutePlan> activeOpt = routePlanRepository.findByTripIdAndIsActiveTrue(trip.getId());
        if (activeOpt.isEmpty()) {
            log.debug("No active RoutePlan for trip {} — skip route bridge on reroute confirm", trip.getId());
            return;
        }
        RoutePlan active = activeOpt.get();
        List<RouteLeg> legs = routeLegRepository.findByRoutePlanIdOrderByLegSequenceAsc(active.getId());
        if (legs.isEmpty()) {
            return;
        }

        RouteLeg targetLeg = legs.stream()
                .filter(leg -> reroute.getStopId() != null
                        && leg.getEndStop() != null
                        && reroute.getStopId().equals(leg.getEndStop().getId()))
                .findFirst()
                .orElse(legs.getFirst());

        int selectedDistanceMeters = selected.get("distanceKm") instanceof Number n
                ? (int) Math.round(n.doubleValue() * 1000)
                : targetLeg.getDistanceMeters();
        int selectedDurationSeconds = selected.get("durationMinutes") instanceof Number n
                ? n.intValue() * 60
                : targetLeg.getDurationSeconds();

        Integer distanceDiff = selectedDistanceMeters - targetLeg.getDistanceMeters();
        Integer durationDiff = selectedDurationSeconds - targetLeg.getDurationSeconds();
        String suggestionType = stringVal(selected.get("route"));
        if (suggestionType == null) {
            suggestionType = "REROUTE";
        }

        String polyline = targetLeg.getPolyline();
        List<Map<String, Object>> steps = targetLeg.getSteps();
        DirectionsResponse.Route refreshed = refreshDirections(trip, targetLeg);
        if (refreshed != null) {
            if (refreshed.getPolyline() != null) {
                polyline = refreshed.getPolyline();
            }
            List<Map<String, Object>> guidance = refreshed.toGuidanceSteps();
            if (!guidance.isEmpty()) {
                steps = guidance;
            }
        }

        RouteSuggestion suggestion = routeSuggestionRepository.save(RouteSuggestion.builder()
                .routeLeg(targetLeg)
                .suggestionType(suggestionType)
                .distanceDiffMeters(distanceDiff)
                .durationDiffSeconds(durationDiff)
                .proposedPolyline(polyline)
                .isAccepted(true)
                .build());

        RoutePlan newPlan = routePlanWriter.cloneActiveWithLegOverride(
                trip,
                active,
                legs,
                targetLeg.getId(),
                selectedDistanceMeters,
                selectedDurationSeconds,
                polyline,
                steps);

        Map<String, Object> cache = trip.getRoutePlan() != null
                ? new HashMap<>(trip.getRoutePlan())
                : new HashMap<>();
        cache.put("activeRoutePlanId", newPlan.getId());
        cache.put("planVersion", newPlan.getPlanVersion());
        cache.put("totalDistanceMeters", newPlan.getTotalDistanceMeters());
        cache.put("totalTravelDurationSeconds", newPlan.getTotalDurationSeconds());
        cache.put("lastRerouteSuggestionId", suggestion.getId());
        trip.setRoutePlan(cache);
        tripRepository.save(trip);
    }

    @Override
    public Optional<TripRerouteResponse> latestConfirmed(Long tripId) {
        return tripRerouteRepository.findFirstByTripIdAndConfirmedAtIsNotNullOrderByConfirmedAtDesc(tripId)
                .map(this::fromEntity);
    }

    private TripRerouteResponse fromEntity(TripReroute entity) {
        Map<String, Object> confirmed = entity.getConfirmedJson();
        if (confirmed == null) {
            return null;
        }
        return TripRerouteResponse.builder()
                .index(intVal(confirmed.get("index")))
                .route(stringVal(confirmed.get("route")))
                .distanceKm(doubleVal(confirmed.get("distanceKm")))
                .durationMinutes(intVal(confirmed.get("durationMinutes")))
                .extraMs(longVal(confirmed.get("extraMs")))
                .eta(stringVal(confirmed.get("eta")))
                .stopNumber(entity.getStopNumber())
                .confirmedAt(stringVal(confirmed.get("confirmedAt")))
                .confirmedBy(stringVal(confirmed.get("confirmedBy")))
                .build();
    }

    private LegMetrics resolveBaseline(LatLng from, LatLng to) {
        double haversineKm = haversineKm(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        long haversineSeconds = Math.max(1, Math.round((haversineKm / FALLBACK_SPEED_KMH) * 3600));

        try {
            DirectionsResponse directions = goongMapsClient.getDirections(from, to);
            if (directions != null && directions.getRoutes() != null && !directions.getRoutes().isEmpty()) {
                DirectionsResponse.Route route = directions.getRoutes().getFirst();
                long seconds = route.getDurationSeconds();
                double km = route.getDistanceMeters() / 1000.0;
                if (seconds > 0 && km > 0) {
                    return new LegMetrics(km, seconds);
                }
            }
        } catch (ServiceUnavailableException ex) {
            log.debug("Goong unavailable for reroute baseline: {}", ex.getMessage());
        } catch (Exception ex) {
            log.debug("Goong failed for reroute baseline: {}", ex.getMessage());
        }
        return new LegMetrics(haversineKm, haversineSeconds);
    }

    private List<RerouteOptionResponse> buildOptions(LegMetrics baseline, LocalDateTime requestedAt) {
        List<RerouteOptionResponse> options = new ArrayList<>();
        // Fastest (Goong / baseline)
        options.add(option(0, RerouteRouteType.BYPASS, baseline.distanceKm * 1.15, baseline.durationSeconds * 1.15, baseline, requestedAt));
        options.add(option(1, RerouteRouteType.RING_ROAD, baseline.distanceKm * 1.30, baseline.durationSeconds * 1.30, baseline, requestedAt));
        if (baseline.distanceKm >= HIGHWAY_MIN_KM) {
            // Longer distance but faster effective speed (~70 km/h vs 30)
            double highwayKm = baseline.distanceKm * 1.50;
            long highwaySeconds = Math.max(1, Math.round((highwayKm / 70.0) * 3600));
            options.add(option(2, RerouteRouteType.HIGHWAY, highwayKm, highwaySeconds, baseline, requestedAt));
        } else {
            // Ensure at least 2–3 options: slight variant of baseline
            options.add(option(2, RerouteRouteType.BYPASS, baseline.distanceKm * 1.05, baseline.durationSeconds * 0.95, baseline, requestedAt));
        }
        return options;
    }

    private RerouteOptionResponse option(
            int index,
            RerouteRouteType route,
            double distanceKm,
            double durationSeconds,
            LegMetrics baseline,
            LocalDateTime requestedAt
    ) {
        long durationMs = Math.round(durationSeconds * 1000);
        long baselineMs = baseline.durationSeconds * 1000;
        return RerouteOptionResponse.builder()
                .index(index)
                .route(route.name())
                .distanceKm(Math.round(distanceKm * 10.0) / 10.0)
                .durationMinutes((int) Math.max(1, Math.round(durationSeconds / 60.0)))
                .extraMs(Math.max(0, durationMs - baselineMs))
                .eta(MonitoringTime.toIso(requestedAt.plusSeconds(Math.round(durationSeconds))))
                .build();
    }

    private Map<String, Object> toProposalMap(RerouteProposalResponse proposal) {
        Map<String, Object> map = new HashMap<>();
        map.put("tripId", proposal.getTripId());
        map.put("requestedAt", proposal.getRequestedAt());
        map.put("stopId", proposal.getStopId());
        map.put("stopNumber", proposal.getStopNumber());
        map.put("isMockResult", proposal.getIsMockResult());
        List<Map<String, Object>> options = new ArrayList<>();
        for (RerouteOptionResponse option : proposal.getOptions()) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", option.getIndex());
            item.put("route", option.getRoute());
            item.put("distanceKm", option.getDistanceKm());
            item.put("durationMinutes", option.getDurationMinutes());
            item.put("extraMs", option.getExtraMs());
            item.put("eta", option.getEta());
            options.add(item);
        }
        map.put("options", options);
        return map;
    }

    private Map<String, Object> toConfirmedMap(TripRerouteResponse response) {
        Map<String, Object> map = new HashMap<>();
        map.put("index", response.getIndex());
        map.put("route", response.getRoute());
        map.put("distanceKm", response.getDistanceKm());
        map.put("durationMinutes", response.getDurationMinutes());
        map.put("extraMs", response.getExtraMs());
        map.put("eta", response.getEta());
        map.put("stopNumber", response.getStopNumber());
        map.put("confirmedAt", response.getConfirmedAt());
        map.put("confirmedBy", response.getConfirmedBy());
        return map;
    }

    private DirectionsResponse.Route refreshDirections(Trip trip, RouteLeg targetLeg) {
        DeliveryStop end = targetLeg.getEndStop();
        if (end == null || end.getLatitude() == null || end.getLongitude() == null) {
            return null;
        }
        GpsTracking gps = gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId()).orElse(null);
        if (gps == null || gps.getLatitude() == null || gps.getLongitude() == null) {
            return null;
        }
        try {
            DirectionsResponse directions = goongMapsClient.getDirections(
                    new LatLng(gps.getLatitude().doubleValue(), gps.getLongitude().doubleValue()),
                    new LatLng(end.getLatitude().doubleValue(), end.getLongitude().doubleValue()));
            if (directions == null || directions.getRoutes() == null || directions.getRoutes().isEmpty()) {
                return null;
            }
            return directions.getRoutes().getFirst();
        } catch (ServiceUnavailableException ex) {
            log.debug("Goong unavailable when refreshing reroute geometry: {}", ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.debug("Goong failed when refreshing reroute geometry: {}", ex.getMessage());
            return null;
        }
    }

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double doubleVal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value != null ? Double.parseDouble(String.valueOf(value)) : null;
    }

    private Integer intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value != null ? Integer.parseInt(String.valueOf(value)) : null;
    }

    private Long longVal(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value != null ? Long.parseLong(String.valueOf(value)) : null;
    }

    private record LegMetrics(double distanceKm, long durationSeconds) {
    }
}
