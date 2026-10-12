package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripLiveStopResponse;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import fu.se184491.loadmaster_be.repository.monitoring.EtaRiskAlertRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EtaCalculationServiceImpl implements EtaCalculationService {

    private static final double FALLBACK_SPEED_KMH = 30.0;
    private static final long AT_RISK_MARGIN_MINUTES = 30;

    private final GoongMapsClient goongMapsClient;
    private final DeliveryStopRepository deliveryStopRepository;
    private final EtaRiskAlertRepository etaRiskAlertRepository;

    @Override
    @Transactional
    public List<TripLiveStopResponse> recalculateAndAlert(Trip trip, GpsTracking currentLocation) {
        List<DeliveryStop> allStops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        Set<DeliveryStopStatus> remaining = EnumSet.of(DeliveryStopStatus.PENDING, DeliveryStopStatus.ARRIVED);
        List<DeliveryStop> pendingStops = allStops.stream()
                .filter(stop -> remaining.contains(stop.getStatus()))
                .filter(stop -> stop.getLatitude() != null && stop.getLongitude() != null)
                .toList();

        if (pendingStops.isEmpty() || currentLocation == null) {
            return List.of();
        }

        LocalDateTime clock = currentLocation.getRecordedAt() != null
                ? currentLocation.getRecordedAt()
                : MonitoringTime.now();
        LatLng from = new LatLng(
                currentLocation.getLatitude().doubleValue(),
                currentLocation.getLongitude().doubleValue()
        );

        List<TripLiveStopResponse> liveStops = new ArrayList<>();
        for (DeliveryStop stop : pendingStops) {
            LatLng to = new LatLng(stop.getLatitude().doubleValue(), stop.getLongitude().doubleValue());
            long durationSeconds = estimateDurationSeconds(from, to);
            clock = clock.plusSeconds(durationSeconds);

            DeadlineStatus deadlineStatus = null;
            String deadlineIso = null;
            if (stop.getPlannedArrival() != null) {
                deadlineStatus = resolveDeadlineStatus(clock, stop.getPlannedArrival());
                deadlineIso = MonitoringTime.toIso(stop.getPlannedArrival());
                if (deadlineStatus == DeadlineStatus.AT_RISK || deadlineStatus == DeadlineStatus.MISSED) {
                    upsertAlert(trip, stop, deadlineStatus, clock, stop.getPlannedArrival());
                }
            }

            TripLiveStopResponse.TripLiveStopResponseBuilder builder = TripLiveStopResponse.builder()
                    .stopId(String.valueOf(stop.getId()))
                    .number(stop.getStopSequence())
                    .eta(MonitoringTime.toIso(clock))
                    .deadline(deadlineIso)
                    .deadlineStatus(deadlineStatus != null ? deadlineStatus.name() : null);

            if (stop.getStatus() == DeliveryStopStatus.ARRIVED) {
                builder.arrived(true);
            }

            liveStops.add(builder.build());
            from = to;
        }

        return liveStops;
    }

    private long estimateDurationSeconds(LatLng from, LatLng to) {
        try {
            DirectionsResponse directions = goongMapsClient.getDirections(from, to);
            if (directions != null && directions.getRoutes() != null && !directions.getRoutes().isEmpty()) {
                long seconds = directions.getRoutes().getFirst().getDurationSeconds();
                if (seconds > 0) {
                    return seconds;
                }
            }
        } catch (ServiceUnavailableException ex) {
            log.debug("Goong directions unavailable, using haversine fallback: {}", ex.getMessage());
        } catch (Exception ex) {
            log.debug("Goong directions failed, using haversine fallback: {}", ex.getMessage());
        }
        return haversineDurationSeconds(from, to);
    }

    private long haversineDurationSeconds(LatLng from, LatLng to) {
        double km = haversineKm(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        double hours = km / FALLBACK_SPEED_KMH;
        return Math.max(1, Math.round(hours * 3600));
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

    /**
     * OK when ETA ≤ deadline − 30 min; AT_RISK when ETA ≤ deadline; otherwise MISSED.
     */
    private DeadlineStatus resolveDeadlineStatus(LocalDateTime eta, LocalDateTime deadline) {
        LocalDateTime atRiskThreshold = deadline.minusMinutes(AT_RISK_MARGIN_MINUTES);
        if (!eta.isAfter(atRiskThreshold)) {
            return DeadlineStatus.OK;
        }
        if (!eta.isAfter(deadline)) {
            return DeadlineStatus.AT_RISK;
        }
        return DeadlineStatus.MISSED;
    }

    private void upsertAlert(
            Trip trip,
            DeliveryStop stop,
            DeadlineStatus status,
            LocalDateTime eta,
            LocalDateTime deadline
    ) {
        boolean alreadyLogged = etaRiskAlertRepository
                .findFirstByTripIdAndStopIdAndStatusOrderByCreatedAtDesc(trip.getId(), stop.getId(), status)
                .isPresent();
        if (alreadyLogged) {
            return;
        }
        etaRiskAlertRepository.save(EtaRiskAlert.builder()
                .trip(trip)
                .stopId(stop.getId())
                .stopNumber(stop.getStopSequence())
                .status(status)
                .eta(eta)
                .deadline(deadline)
                .createdAt(MonitoringTime.now())
                .build());
    }
}
