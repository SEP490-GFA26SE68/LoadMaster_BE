package fu.se184491.loadmaster_be.controller.monitoring;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.monitoring.ReportExceptionRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ResolveExceptionRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.MonitoringBoardResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.RerouteProposalResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripMonitoringResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripRerouteResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.monitoring.GpsTrackingService;
import fu.se184491.loadmaster_be.service.monitoring.RerouteService;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripMonitoringService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "APIs for live trip monitoring, GPS, exceptions and reroute")
@PreAuthorize("hasAuthority('TRIP_MANAGE')")
public class MonitoringController {

    private final GpsTrackingService gpsTrackingService;
    private final TripMonitoringService tripMonitoringService;
    private final TripExceptionService tripExceptionService;
    private final RerouteService rerouteService;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/trips/{id}/location/latest")
    @Operation(summary = "Latest GPS location for a trip")
    public ResponseEntity<ApiResponse<LocationPointResponse>> getLatestLocation(@PathVariable("id") String id) {
        LocationPointResponse location = gpsTrackingService.getLatestLocation(id, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy vị trí mới nhất thành công", location));
    }

    @GetMapping("/api/trips/{id}/location/history")
    @Operation(summary = "GPS location history for a trip")
    public ResponseEntity<ApiResponse<List<LocationPointResponse>>> getLocationHistory(@PathVariable("id") String id) {
        List<LocationPointResponse> history = gpsTrackingService.getLocationHistory(id, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử vị trí thành công", history));
    }

    @GetMapping("/api/trips/{id}/monitoring")
    @Operation(summary = "Live monitoring snapshot for a trip")
    public ResponseEntity<ApiResponse<TripMonitoringResponse>> getTripMonitoring(@PathVariable("id") String id) {
        TripMonitoringResponse monitoring = tripMonitoringService.getMonitoring(id, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy giám sát chuyến đi thành công", monitoring));
    }

    @GetMapping("/api/trips/monitoring")
    @Operation(summary = "Fleet monitoring list for IN_TRANSIT trips")
    public ResponseEntity<ApiResponse<List<TripMonitoringResponse>>> listFleetMonitoring() {
        List<TripMonitoringResponse> fleet = tripMonitoringService.listFleetMonitoring(currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách giám sát đội xe thành công", fleet));
    }

    @GetMapping("/api/dispatcher/dashboard")
    @Operation(summary = "Dispatcher monitoring board")
    public ResponseEntity<ApiResponse<MonitoringBoardResponse>> getDashboard() {
        MonitoringBoardResponse board = tripMonitoringService.getDashboard(currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy bảng giám sát thành công", board));
    }

    @PostMapping("/api/trips/{id}/exceptions")
    @Operation(summary = "Report a trip-level exception")
    public ResponseEntity<ApiResponse<TripExceptionResponse>> reportException(
            @PathVariable("id") String id,
            @Valid @RequestBody ReportExceptionRequest request
    ) {
        Long userId = currentUserService.getCurrentUser().getId();
        TripExceptionResponse response = tripExceptionService.report(
                id, currentUserService.getCurrentCompanyId(), request, userId);
        return ResponseEntity.ok(ApiResponse.success("Báo cáo sự cố thành công", response));
    }

    @GetMapping("/api/trips/{id}/exceptions")
    @Operation(summary = "List trip exceptions")
    public ResponseEntity<ApiResponse<List<TripExceptionResponse>>> listExceptions(@PathVariable("id") String id) {
        List<TripExceptionResponse> exceptions = tripExceptionService.list(id, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách sự cố thành công", exceptions));
    }

    @PostMapping("/api/trips/{id}/exceptions/{exceptionId}/resolve")
    @Operation(summary = "Resolve a trip exception")
    public ResponseEntity<ApiResponse<TripExceptionResponse>> resolveException(
            @PathVariable("id") String id,
            @PathVariable("exceptionId") String exceptionId,
            @RequestBody(required = false) ResolveExceptionRequest request
    ) {
        Long userId = currentUserService.getCurrentUser().getId();
        TripExceptionResponse response = tripExceptionService.resolveByPathId(
                id, exceptionId, currentUserService.getCurrentCompanyId(), request, userId);
        return ResponseEntity.ok(ApiResponse.success("Xử lý sự cố thành công", response));
    }

    @PostMapping("/api/trips/{id}/exceptions/{exceptionId}/escalate")
    @Operation(summary = "Escalate a trip exception to the company manager")
    public ResponseEntity<ApiResponse<TripExceptionResponse>> escalateException(
            @PathVariable("id") String id,
            @PathVariable("exceptionId") String exceptionId
    ) {
        Long userId = currentUserService.getCurrentUser().getId();
        TripExceptionResponse response = tripExceptionService.escalate(
                id, exceptionId, currentUserService.getCurrentCompanyId(), userId, "NO_ROUTE");
        return ResponseEntity.ok(ApiResponse.success("Chuyển sự cố cho quản lý thành công", response));
    }

    @PostMapping("/api/trips/{id}/reroute")
    @Operation(summary = "Propose alternative routes to the next stop")
    public ResponseEntity<ApiResponse<RerouteProposalResponse>> proposeReroute(@PathVariable("id") String id) {
        RerouteProposalResponse proposal = rerouteService.propose(id, currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.success("Tìm tuyến thay thế thành công", proposal));
    }

    @PostMapping("/api/trips/{id}/reroute/{routeIndex}/confirm")
    @Operation(summary = "Confirm a proposed reroute option")
    public ResponseEntity<ApiResponse<TripRerouteResponse>> confirmReroute(
            @PathVariable("id") String id,
            @PathVariable("routeIndex") int routeIndex
    ) {
        Long userId = currentUserService.getCurrentUser().getId();
        TripRerouteResponse response = rerouteService.confirm(
                id, routeIndex, currentUserService.getCurrentCompanyId(), userId);
        return ResponseEntity.ok(ApiResponse.success("Xác nhận tuyến thay thế thành công", response));
    }
}
