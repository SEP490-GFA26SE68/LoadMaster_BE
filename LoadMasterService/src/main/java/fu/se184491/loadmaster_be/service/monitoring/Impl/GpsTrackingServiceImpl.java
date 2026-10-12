package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.constant.monitoring.LocationSource;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.monitoring.DriverLocationRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GpsTrackingServiceImpl implements GpsTrackingService {

    private final GpsTrackingRepository gpsTrackingRepository;
    private final TripLookupHelper tripLookupHelper;
    private final EtaCalculationService etaCalculationService;

    @Override
    @Transactional
    public LocationPointResponse recordLocation(DriverLocationRequest request) {
        Trip trip = tripLookupHelper.requireTripByIdOrCode(request.getTripId());
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }

        GpsTracking tracking = GpsTracking.builder()
                .trip(trip)
                .latitude(roundCoord(request.getLat()))
                .longitude(roundCoord(request.getLng()))
                .speedKmh(request.getSpeedKmh() != null ? request.getSpeedKmh() : 0.0)
                .heading(request.getHeading() != null ? request.getHeading() : 0.0)
                .source(LocationSource.GPS)
                .recordedAt(MonitoringTime.now())
                .build();

        GpsTracking saved = gpsTrackingRepository.save(tracking);

        try {
            etaCalculationService.recalculateAndAlert(trip, saved);
        } catch (Exception ex) {
            log.warn("ETA recalculation failed for trip {}: {}", trip.getId(), ex.getMessage());
        }

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public LocationPointResponse getLatestLocation(String tripIdOrCode, Long companyId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        return gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId())
                .map(this::toResponse)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationPointResponse> getLocationHistory(String tripIdOrCode, Long companyId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        return gpsTrackingRepository.findByTripIdOrderByRecordedAtAsc(trip.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    private LocationPointResponse toResponse(GpsTracking tracking) {
        return LocationPointResponse.builder()
                .lat(tracking.getLatitude().doubleValue())
                .lng(tracking.getLongitude().doubleValue())
                .speedKmh(tracking.getSpeedKmh() != null ? tracking.getSpeedKmh() : 0.0)
                .heading(tracking.getHeading() != null ? tracking.getHeading() : 0.0)
                .recordedAt(MonitoringTime.toIso(tracking.getRecordedAt()))
                .source(tracking.getSource() != null ? tracking.getSource().name() : LocationSource.GPS.name())
                .build();
    }

    private BigDecimal roundCoord(Double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP);
    }
}
