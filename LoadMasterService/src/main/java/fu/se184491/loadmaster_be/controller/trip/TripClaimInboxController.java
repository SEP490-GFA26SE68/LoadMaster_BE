package fu.se184491.loadmaster_be.controller.trip;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimInboxResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trip-claims")
@RequiredArgsConstructor
public class TripClaimInboxController {

    private final TripClaimService tripClaimService;

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','MANAGER')")
    public ResponseEntity<ApiResponse<List<TripClaimInboxResponse>>> listPendingClaims(
            @RequestParam(required = false) String status
    ) {
        String requestedStatus = status == null ? "PENDING" : status;
        if (!"PENDING".equals(requestedStatus)) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        return ResponseEntity.ok(ApiResponse.success(
                "Lấy hộp thư phiếu xin chuyến thành công",
                tripClaimService.listPendingClaims()
        ));
    }
}
