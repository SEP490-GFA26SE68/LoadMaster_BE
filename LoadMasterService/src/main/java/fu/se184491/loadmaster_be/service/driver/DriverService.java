package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.dto.response.driver.ArriveStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CurrentStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripResponse;
import fu.se184491.loadmaster_be.entity.trip.Trip;

import java.util.List;

public interface DriverService {


    List<DriverTripResponse> listTrips(Long driverId);

    CurrentStopResponse getCurrentStop(Long tripId);

    ConfirmUnloadResponse confirmUnload(Long packageId, Long driverId);

    CompleteStopResponse completeStop(Long stopId, Long driverId);

    /** Tài xế báo đã đến điểm giao hiện tại của chuyến Đang vận chuyển: ghi giờ đến thật. */
    ArriveStopResponse arriveAtStop(Long stopId);

    /** Tài xế xuất phát: chuyến đã xếp xong (Đang xếp hàng) sang Đang vận chuyển. */
    DriverTripResponse startTrip(Long tripId);

    /** Chuyến của công ty người đăng nhập và được giao cho chính tài xế đó. */
    Trip requireOwnTrip(String tripIdOrCode);
}
