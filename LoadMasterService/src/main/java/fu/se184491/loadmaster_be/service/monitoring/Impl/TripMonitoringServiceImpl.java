package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.monitoring.EtaRiskAlertResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.MonitoringBoardResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripLiveStopResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripMonitoringResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripRerouteResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.monitoring.EtaRiskAlertRepository;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.RerouteService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.monitoring.TripMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TripMonitoringServiceImpl implements TripMonitoringService {

    private static final int IN_TRANSIT_REFRESH_MS = 30_000;

    private final TripLookupHelper tripLookupHelper;
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final GpsTrackingRepository gpsTrackingRepository;
    private final EtaRiskAlertRepository etaRiskAlertRepository;
    private final TripExceptionRepository tripExceptionRepository;
    private final EtaCalculationService etaCalculationService;
    private final TripExceptionService tripExceptionService;
    private final RerouteService rerouteService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public TripMonitoringResponse getMonitoring(String tripIdOrCode, Long companyId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        return assemble(trip);
    }

    @Override
    @Transactional
    public List<TripMonitoringResponse> listFleetMonitoring(Long companyId) {
        return tripRepository.findByCompanyIdAndStatus(companyId, TripStatus.IN_TRANSIT).stream()
                .map(this::assemble)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MonitoringBoardResponse getDashboard(Long companyId) {
        List<Trip> trips = tripRepository.findByCompanyIdAndStatus(companyId, TripStatus.IN_TRANSIT);
        Set<Long> userIds = new HashSet<>();
        List<MonitoringBoardResponse.MonitoringTripResponse> boardTrips = new ArrayList<>();

        for (Trip trip : trips) {
            List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
            Vehicle vehicle = trip.getVehicle();
            User driver = trip.getDriver();
            if (driver != null) {
                userIds.add(driver.getId());
            }

            List<TripException> exceptions = tripExceptionRepository.findByTripIdOrderByReportedAtAsc(trip.getId());
            for (TripException exception : exceptions) {
                if (exception.getReportedBy() != null) {
                    userIds.add(exception.getReportedBy().getId());
                }
                if (exception.getResolvedBy() != null) {
                    userIds.add(exception.getResolvedBy().getId());
                }
                if (exception.getEscalatedBy() != null) {
                    userIds.add(exception.getEscalatedBy().getId());
                }
            }

            boardTrips.add(MonitoringBoardResponse.MonitoringTripResponse.builder()
                    .tripId(trip.getTripCode())
                    .name(trip.getTripCode())
                    .vehicleName(resolveVehicleName(vehicle))
                    .driverName(driver != null ? driver.getFullName() : null)
                    .depot(resolveDepot(trip, stops))
                    .stops(stops.stream().map(this::toBoardStop).toList())
                    .build());
        }

        Map<String, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(user ->
                    userNames.put(String.valueOf(user.getId()), user.getFullName()));
        }

        return MonitoringBoardResponse.builder()
                .trips(boardTrips)
                .userNames(userNames)
                .build();
    }

    private TripMonitoringResponse assemble(Trip trip) {
        GpsTracking latestGps = gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId())
                .orElse(null);

        LocationPointResponse location = latestGps != null ? toLocation(latestGps) : null;

        List<TripLiveStopResponse> stops = List.of();
        if (latestGps != null && trip.getStatus() == TripStatus.IN_TRANSIT) {
            stops = etaCalculationService.recalculateAndAlert(trip, latestGps);
        }

        List<EtaRiskAlertResponse> alerts = etaRiskAlertRepository.findByTripIdOrderByCreatedAtAsc(trip.getId()).stream()
                .map(alert -> toAlert(alert, trip.getTripCode()))
                .toList();

        Long companyId = trip.getCompany() != null ? trip.getCompany().getId() : null;
        List<TripExceptionResponse> exceptions = companyId != null
                ? tripExceptionService.list(trip.getTripCode(), companyId)
                : List.of();

        TripRerouteResponse reroute = rerouteService.latestConfirmed(trip.getId()).orElse(null);

        Integer refreshMs = trip.getStatus() == TripStatus.IN_TRANSIT ? IN_TRANSIT_REFRESH_MS : null;

        return TripMonitoringResponse.builder()
                .tripId(trip.getTripCode())
                .location(location)
                .stops(stops)
                .alerts(alerts)
                .exceptions(exceptions)
                .reroute(reroute)
                .refreshMs(refreshMs)
                .isMockResult(false)
                .build();
    }

    private LocationPointResponse toLocation(GpsTracking tracking) {
        return LocationPointResponse.builder()
                .lat(tracking.getLatitude().doubleValue())
                .lng(tracking.getLongitude().doubleValue())
                .speedKmh(tracking.getSpeedKmh() != null ? tracking.getSpeedKmh() : 0.0)
                .heading(tracking.getHeading() != null ? tracking.getHeading() : 0.0)
                .recordedAt(MonitoringTime.toIso(tracking.getRecordedAt()))
                .source(tracking.getSource() != null ? tracking.getSource().name() : "GPS")
                .build();
    }

    private EtaRiskAlertResponse toAlert(EtaRiskAlert alert, String tripCode) {
        return EtaRiskAlertResponse.builder()
                .eventId("ETA-" + alert.getId())
                .at(MonitoringTime.toIso(alert.getCreatedAt()))
                .tripId(tripCode)
                .stopId(alert.getStopId() != null ? String.valueOf(alert.getStopId()) : null)
                .stopNumber(alert.getStopNumber())
                .status(alert.getStatus() != null ? alert.getStatus().name() : null)
                .eta(MonitoringTime.toIso(alert.getEta()))
                .deadline(MonitoringTime.toIso(alert.getDeadline()))
                .build();
    }

    private MonitoringBoardResponse.MonitoringStopResponse toBoardStop(DeliveryStop stop) {
        MonitoringBoardResponse.MonitoringStopResponse.MonitoringStopResponseBuilder builder =
                MonitoringBoardResponse.MonitoringStopResponse.builder()
                        .id(String.valueOf(stop.getId()))
                        .number(stop.getStopSequence())
                        .name(stop.getStopName() != null ? stop.getStopName() : stop.getAddress());

        if (stop.getLatitude() != null && stop.getLongitude() != null) {
            builder.lat(stop.getLatitude().doubleValue()).lng(stop.getLongitude().doubleValue());
        }
        if (stop.getActualArrival() != null) {
            builder.arrivedAt(MonitoringTime.toIso(stop.getActualArrival()));
        }
        if (stop.getStatus() == DeliveryStopStatus.COMPLETED && stop.getActualArrival() != null) {
            builder.completedAt(MonitoringTime.toIso(stop.getActualArrival()));
        }
        return builder.build();
    }

    private String resolveVehicleName(Vehicle vehicle) {
        if (vehicle == null) {
            return "Unknown";
        }
        String plate = vehicle.getLicensePlate();
        String typeName = vehicle.getVehicleType() != null ? vehicle.getVehicleType().getName() : null;
        if (typeName != null && plate != null) {
            return typeName + " " + plate;
        }
        return plate != null ? plate : (typeName != null ? typeName : "Unknown");
    }

    private MonitoringBoardResponse.DepotResponse resolveDepot(Trip trip, List<DeliveryStop> stops) {
        Map<String, Object> routePlan = trip.getRoutePlan();
        if (routePlan != null) {
            Double lat = number(routePlan.get("originLat"));
            Double lng = number(routePlan.get("originLng"));
            String name = string(routePlan.get("originName"));
            if (lat != null && lng != null) {
                return MonitoringBoardResponse.DepotResponse.builder()
                        .name(name != null ? name : "Kho")
                        .lat(lat)
                        .lng(lng)
                        .build();
            }
            Object origin = routePlan.get("origin");
            if (origin instanceof Map<?, ?> originMap) {
                lat = number(originMap.get("latitude"));
                if (lat == null) {
                    lat = number(originMap.get("lat"));
                }
                lng = number(originMap.get("longitude"));
                if (lng == null) {
                    lng = number(originMap.get("lng"));
                }
                String originName = string(originMap.get("name"));
                if (lat != null && lng != null) {
                    return MonitoringBoardResponse.DepotResponse.builder()
                            .name(originName != null ? originName : "Kho")
                            .lat(lat)
                            .lng(lng)
                            .build();
                }
            }
        }

        if (!stops.isEmpty()) {
            DeliveryStop first = stops.getFirst();
            if (first.getLatitude() != null && first.getLongitude() != null) {
                return MonitoringBoardResponse.DepotResponse.builder()
                        .name(first.getStopName() != null ? first.getStopName() : "Kho")
                        .lat(first.getLatitude().doubleValue())
                        .lng(first.getLongitude().doubleValue())
                        .build();
            }
        }

        return MonitoringBoardResponse.DepotResponse.builder()
                .name("Kho")
                .lat(0.0)
                .lng(0.0)
                .build();
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
