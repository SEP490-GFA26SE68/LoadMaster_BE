package fu.se184491.loadmaster_be.controller.driver;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.trip.TripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
@Tag(name = "Driver Trip Claims", description = "Driver trip claim APIs")
@PreAuthorize("hasRole('DRIVER')")
public class DriverTripClaimController {

    private final TripClaimService tripClaimService;

    @GetMapping("/open-trips")
    @Operation(summary = "List open trips for driver claim", description = "Retrieve trips of current company without driver, in DRAFT or PLANNED status")
    public ResponseEntity<ApiResponse<List<OpenTripResponse>>> getOpenTrips() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách chuyến đang mở thành công",
                tripClaimService.getOpenTrips()
        ));
    }

    @PostMapping("/trips/{tripId}/claim")
    @Operation(summary = "Claim a trip", description = "Driver requests to claim an open trip")
    public ResponseEntity<ApiResponse<TripClaimResponse>> claimTrip(
            @PathVariable Long tripId,
            @Valid @RequestBody(required = false) TripClaimRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Gửi phiếu xin chuyến thành công",
                tripClaimService.claimTrip(tripId, request)
        ));
    }

    @PostMapping("/trips/{tripId}/claim/cancel")
    @Operation(summary = "Cancel a pending claim", description = "Driver cancels own pending claim for a trip")
    public ResponseEntity<ApiResponse<TripClaimResponse>> cancelClaim(
            @PathVariable Long tripId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Rút phiếu xin chuyến thành công",
                tripClaimService.cancelClaim(tripId)
        ));
    }
}
