package fu.se184491.loadmaster_be.controller.pickup;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.pickup.ApprovePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.CreatePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.RejectPickupRequest;
import fu.se184491.loadmaster_be.dto.response.pickup.PickupRequestResponse;
import fu.se184491.loadmaster_be.service.pickup.PickupRequestService;
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

/** Yêu cầu nhận hàng dọc đường của một chuyến (mã số hoặc mã chuyến). */
@RestController
@RequestMapping("/api/trips/{tripId}/pickup-requests")
@RequiredArgsConstructor
@Tag(name = "Pickup requests", description = "En-route pickup requests of an IN_TRANSIT trip")
public class PickupRequestController {

    private final PickupRequestService pickupRequestService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'DRIVER')")
    @Operation(summary = "List pickup requests of a trip")
    public ResponseEntity<ApiResponse<List<PickupRequestResponse>>> list(@PathVariable("tripId") String tripId) {
        return ResponseEntity.ok(ApiResponse.success("Lấy yêu cầu nhận hàng thành công", pickupRequestService.list(tripId)));
    }

    @GetMapping("/{pickupId}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'DRIVER')")
    @Operation(summary = "Get a pickup request")
    public ResponseEntity<ApiResponse<PickupRequestResponse>> get(@PathVariable("tripId") String tripId,
                                                                  @PathVariable("pickupId") Long pickupId) {
        return ResponseEntity.ok(ApiResponse.success("Lấy yêu cầu nhận hàng thành công",
                pickupRequestService.get(tripId, pickupId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'DRIVER')")
    @Operation(summary = "Create a pickup request", description = "Dispatcher, or the trip's own driver; the trip must be IN_TRANSIT. The ten rules are evaluated before saving.")
    public ResponseEntity<ApiResponse<PickupRequestResponse>> create(@PathVariable("tripId") String tripId,
                                                                     @Valid @RequestBody CreatePickupRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã tạo yêu cầu nhận hàng", pickupRequestService.create(tripId, request)));
    }

    @PostMapping("/{pickupId}/validate")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'DRIVER')")
    @Operation(summary = "Re-evaluate the ten rules", description = "Re-checks the rules against the trip as it is now; stores the result on the request")
    public ResponseEntity<ApiResponse<PickupRequestResponse>> validate(@PathVariable("tripId") String tripId,
                                                                       @PathVariable("pickupId") Long pickupId) {
        return ResponseEntity.ok(ApiResponse.success("Đã kiểm lại luật nhận hàng",
                pickupRequestService.validate(tripId, pickupId)));
    }

    @PostMapping("/{pickupId}/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Approve a pickup request", description = "A reason is required while any rule fails. Creates packages with QR, inserts the pickup and delivery stops after the current stop; the approved load plan is unchanged.")
    public ResponseEntity<ApiResponse<PickupRequestResponse>> approve(@PathVariable("tripId") String tripId,
                                                                      @PathVariable("pickupId") Long pickupId,
                                                                      @Valid @RequestBody(required = false) ApprovePickupRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã duyệt yêu cầu nhận hàng",
                pickupRequestService.approve(tripId, pickupId, request)));
    }

    @PostMapping("/{pickupId}/reject")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Reject a pickup request", description = "A reason is required")
    public ResponseEntity<ApiResponse<PickupRequestResponse>> reject(@PathVariable("tripId") String tripId,
                                                                     @PathVariable("pickupId") Long pickupId,
                                                                     @Valid @RequestBody(required = false) RejectPickupRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã từ chối yêu cầu nhận hàng",
                pickupRequestService.reject(tripId, pickupId, request)));
    }
}
