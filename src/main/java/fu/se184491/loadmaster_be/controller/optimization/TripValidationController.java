package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.ValidationResponse;
import fu.se184491.loadmaster_be.service.optimize.TripValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripValidationController {

    private final TripValidationService tripValidationService;

    /**
     * GET /api/trips/{id}/validation
     * Validate a trip before running optimization.
     * Returns 200 with ValidationResult regardless of canOptimize flag.
     */
    @GetMapping("/{id}/validation")
    @PreAuthorize("hasAuthority('OPTIMIZATION_MANAGE')")
    public ResponseEntity<ApiResponse<ValidationResponse>> validateTrip(@PathVariable Long id) {
        ValidationResponse result = tripValidationService.validate(id);
        return ResponseEntity.ok(ApiResponse.success("Kết quả kiểm tra chuyến đi", result));
    }
}
