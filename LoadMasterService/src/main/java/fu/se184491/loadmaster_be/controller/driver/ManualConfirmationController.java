package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.service.driver.DriverDeliveryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Xác nhận tay khi dỡ của tài xế: điều phối viên duyệt hoặc từ chối, quản lý công ty chỉ xem. */
@RestController
@RequestMapping("/api/trips/{tripId}/manual-confirmations")
@RequiredArgsConstructor
@Tag(name = "Manual confirmations", description = "Dispatcher review of drivers' manual unload confirmations")
public class ManualConfirmationController {

    private final DriverDeliveryService driverDeliveryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    @Operation(summary = "Pending manual confirmations of a trip")
    public ResponseEntity<ApiResponse<List<ManualConfirmationResponse>>> listPending(@PathVariable("tripId") String tripId) {
        return ResponseEntity.ok(ApiResponse.success("Lấy xác nhận tay chờ duyệt thành công",
                driverDeliveryService.listPendingManualConfirmations(tripId)));
    }

    @PostMapping("/{confirmationId}/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Approve a manual confirmation", description = "The package keeps its unloaded result")
    public ResponseEntity<ApiResponse<ManualConfirmationResponse>> approve(
            @PathVariable("tripId") String tripId,
            @PathVariable("confirmationId") Long confirmationId) {
        return ResponseEntity.ok(ApiResponse.success("Đã duyệt xác nhận tay",
                driverDeliveryService.approveManualConfirmation(tripId, confirmationId)));
    }

    @PostMapping("/{confirmationId}/reject")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Reject a manual confirmation", description = "The package goes back to not unloaded; a reason is required")
    public ResponseEntity<ApiResponse<ManualConfirmationResponse>> reject(
            @PathVariable("tripId") String tripId,
            @PathVariable("confirmationId") Long confirmationId,
            @Valid @RequestBody RejectManualConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã từ chối xác nhận tay",
                driverDeliveryService.rejectManualConfirmation(tripId, confirmationId, request)));
    }
}
