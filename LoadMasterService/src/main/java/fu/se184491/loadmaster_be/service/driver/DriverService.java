package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CurrentStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripResponse;

import java.util.List;

public interface DriverService {

    List<DriverTripResponse> getDriverTrips(Long driverId);

    CurrentStopResponse getCurrentStop(Long tripId);

    ConfirmUnloadResponse confirmUnload(Long packageId, Long driverId);

    CompleteStopResponse completeStop(Long stopId, Long driverId);
}
