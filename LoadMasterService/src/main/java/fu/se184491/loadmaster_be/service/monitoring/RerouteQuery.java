package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.response.monitoring.TripRerouteResponse;

import java.util.Optional;

public interface RerouteQuery {

    Optional<TripRerouteResponse> latestConfirmed(Long tripId);
}
