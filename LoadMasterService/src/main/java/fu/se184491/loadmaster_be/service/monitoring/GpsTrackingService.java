package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.dto.request.monitoring.DriverLocationRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;

import java.util.List;

public interface GpsTrackingService {

    LocationPointResponse recordLocation(DriverLocationRequest request);

    LocationPointResponse getLatestLocation(String tripIdOrCode, Long companyId);

    List<LocationPointResponse> getLocationHistory(String tripIdOrCode, Long companyId);
}
