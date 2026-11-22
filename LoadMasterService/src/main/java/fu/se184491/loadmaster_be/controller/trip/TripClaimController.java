package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.trip.RejectTripClaimRequest;
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
@RequestMapping("/api/trips/{tripId}/claims")
@RequiredArgsConstructor
@Tag(name = "Trip Claims", description = "Dispatcher management of trip claims")
public class TripClaimController {

    private final TripClaimService tripClaimService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    @Operation(summary = "List claims for a trip", description = "View driver claims for a specific trip")
    public ResponseEntity<ApiResponse<List<TripClaimResponse>>> getClaims(
            @PathVariable Long tripId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách phiếu xin chuyến thành công",
                tripClaimService.getTripClaims(tripId)
        ));
    }

    @PostMapping("/{claimId}/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Approve a claim", description = "Assign driver to trip and reject all other pending claims")
    public ResponseEntity<ApiResponse<TripClaimResponse>> approveClaim(
            @PathVariable Long tripId,
            @PathVariable Long claimId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Duyệt phiếu xin chuyến thành công",
                tripClaimService.approveClaim(tripId, claimId)
        ));
    }

    @PostMapping("/{claimId}/reject")
    @PreAuthorize("hasRole('DISPATCHER')")
    @Operation(summary = "Reject a claim", description = "Reject a claim with required reason")
    public ResponseEntity<ApiResponse<TripClaimResponse>> rejectClaim(
            @PathVariable Long tripId,
            @PathVariable Long claimId,
            @Valid @RequestBody RejectTripClaimRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Từ chối phiếu xin chuyến thành công",
                tripClaimService.rejectClaim(tripId, claimId, request)
        ));
    }
}
