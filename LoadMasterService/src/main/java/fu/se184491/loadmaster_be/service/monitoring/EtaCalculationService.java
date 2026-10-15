package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.response.monitoring.TripLiveStopResponse;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.trip.Trip;

import java.util.List;

public interface EtaCalculationService {

    /**
     * Estimate ETAs for remaining PENDING/ARRIVED stops from current GPS, update risk alerts.
     */
    List<TripLiveStopResponse> recalculateAndAlert(Trip trip, GpsTracking currentLocation);
}
