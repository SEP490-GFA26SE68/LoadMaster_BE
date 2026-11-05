package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.monitoring.DriverLocationRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ReportExceptionRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ArriveStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CompleteStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ConfirmUnloadResponse;
import fu.se184491.loadmaster_be.dto.response.driver.CurrentStopResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
@Tag(name = "Driver", description = "APIs for driver trips and delivery stops")
@PreAuthorize("hasRole('DRIVER')")
public class DriverController {

    private final DriverService driverService;
    private final GpsTrackingService gpsTrackingService;
    private final TripExceptionService tripExceptionService;

    @GetMapping("/trips")
    @Operation(summary = "List driver trips", description = "Retrieve trips assigned to the driver, newest departure first")
    public ResponseEntity<ApiResponse<List<DriverTripResponse>>> listTrips(
            @RequestParam(value = "driverId", required = false) Long driverId) {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách chuyến đi của tài xế thành công", driverService.listTrips(driverId)));
    }

    @GetMapping("/trips/{id}/current-stop")
    @Operation(summary = "Get current stop", description = "Retrieve current pending stop and recommended packages to unload in LIFO order")
    public ResponseEntity<ApiResponse<CurrentStopResponse>> getCurrentStop(
            @PathVariable("id") Long id) {
        CurrentStopResponse currentStop = driverService.getCurrentStop(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy thông tin điểm dừng hiện tại thành công", currentStop));
    }

    @PostMapping("/packages/{id}/unload")
    @Operation(summary = "Confirm package unload", description = "Driver confirms that a package has been unloaded at the current stop")
    public ResponseEntity<ApiResponse<ConfirmUnloadResponse>> confirmUnload(
            @PathVariable("id") Long id,
            @RequestParam(value = "driverId", required = false) Long driverId) {
        ConfirmUnloadResponse response = driverService.confirmUnload(id, driverId);
        return ResponseEntity.ok(ApiResponse.success("Xác nhận dỡ kiện hàng thành công", response));
    }

    @PostMapping("/stops/{id}/arrive")
    @Operation(summary = "Arrive at delivery stop", description = "Driver reports arrival at the current stop of an IN_TRANSIT trip; records the actual arrival time")
    public ResponseEntity<ApiResponse<ArriveStopResponse>> arriveAtStop(
            @PathVariable("id") Long id) {
        ArriveStopResponse response = driverService.arriveAtStop(id);
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận đến điểm giao", response));
    }

    @PostMapping("/stops/{id}/complete")
    @Operation(summary = "Complete delivery stop", description = "Driver marks a delivery stop as completed after all packages have been unloaded")
    public ResponseEntity<ApiResponse<CompleteStopResponse>> completeStop(
            @PathVariable("id") Long id,
            @RequestParam(value = "driverId", required = false) Long driverId) {
        CompleteStopResponse response = driverService.completeStop(id, driverId);
        return ResponseEntity.ok(ApiResponse.success("Hoàn thành điểm giao hàng thành công", response));
    }

    @PostMapping("/location")
    @Operation(summary = "Record driver GPS location", description = "Driver phone posts a GPS fix for an IN_TRANSIT trip")
    public ResponseEntity<ApiResponse<LocationPointResponse>> recordLocation(
            @Valid @RequestBody DriverLocationRequest request) {
        // Chỉ ghi vị trí cho chuyến của chính tài xế đang đăng nhập.
        driverService.requireOwnTrip(request.getTripId());
        LocationPointResponse point = gpsTrackingService.recordLocation(request);
        return ResponseEntity.ok(ApiResponse.success("Ghi nhận vị trí GPS thành công", point));
    }

    @PostMapping("/trips/{id}/start")
    @Operation(summary = "Start trip", description = "Driver departs after the warehouse has completed loading: LOADING -> IN_TRANSIT")
    public ResponseEntity<ApiResponse<DriverTripResponse>> startTrip(
            @PathVariable("id") Long id) {
        DriverTripResponse response = driverService.startTrip(id);
        return ResponseEntity.ok(ApiResponse.success("Xuất phát chuyến thành công", response));
    }

    @PostMapping("/trips/{id}/exceptions")
    @Operation(summary = "Report a trip-level exception from the driver app")
    public ResponseEntity<ApiResponse<TripExceptionResponse>> reportException(
            @PathVariable("id") String id,
            @Valid @RequestBody ReportExceptionRequest request,
            @RequestParam(value = "driverId", required = false) Long driverId
    ) {
        Trip trip = driverService.requireOwnTrip(id);
        if (driverId != null && !driverId.equals(trip.getDriver().getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        TripExceptionResponse response = tripExceptionService.report(
                String.valueOf(trip.getId()), trip.getCompany().getId(), request, trip.getDriver().getId());
        return ResponseEntity.ok(ApiResponse.success("Báo cáo sự cố thành công", response));
    }
}
