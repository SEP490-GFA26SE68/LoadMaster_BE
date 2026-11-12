package fu.se184491.loadmaster_be.service.driver;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
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

    /** Dỡ một kiện, đối chiếu bằng nhãn theo {@code method} (vắng là QR). */
    ConfirmUnloadResponse confirmUnload(Long packageId, Long driverId, LabelMethod method);

    default ConfirmUnloadResponse confirmUnload(Long packageId, Long driverId) {
        return confirmUnload(packageId, driverId, null);
    }

    CompleteStopResponse completeStop(Long stopId, Long driverId);

    /** Tài xế báo đã đến điểm giao hiện tại của chuyến Đang vận chuyển: ghi giờ đến thật. */
    ArriveStopResponse arriveAtStop(Long stopId);

    /** Tài xế xuất phát: chuyến đã xếp xong (Đang xếp hàng) sang Đang vận chuyển. */
    DriverTripResponse startTrip(Long tripId);

    /** Chuyến của công ty người đăng nhập và được giao cho chính tài xế đó. */
    Trip requireOwnTrip(String tripIdOrCode);
}
