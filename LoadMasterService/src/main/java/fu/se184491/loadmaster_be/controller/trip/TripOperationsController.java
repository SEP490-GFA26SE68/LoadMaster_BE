package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.trip.ReorderStopsRequest;
import fu.se184491.loadmaster_be.dto.response.trip.ReorderStopsResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripReportResponse;
import fu.se184491.loadmaster_be.service.trip.StopReorderService;
import fu.se184491.loadmaster_be.service.trip.TripReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Báo cáo chuyến và đổi thứ tự điểm khi xe đang chạy; {id} là mã số hoặc mã chuyến. */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripOperationsController {

    private final TripReportService tripReportService;
    private final StopReorderService stopReorderService;

    @GetMapping("/{id}/report")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<TripReportResponse>> getReport(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(tripReportService.getReport(id)));
    }

    @PostMapping("/{id}/stops/reorder")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<ApiResponse<ReorderStopsResponse>> reorderStops(
            @PathVariable String id,
            @Valid @RequestBody ReorderStopsRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(stopReorderService.reorder(id, request.stopIds())));
    }
}
