package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.ValidationResponse;
import fu.se184491.loadmaster_be.service.optimization.TripValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
@Tag(name = "Trip Validation", description = "APIs for validating trip before optimization")
public class TripValidationController {

    private final TripValidationService tripValidationService;

    /**
     * GET /api/trips/{id}/validation
     * Validate a trip before running optimization (FR-OPT-01 / S3-01 / S3-02).
     * Returns 200 with ValidationResult regardless of canOptimize flag.
     */
    @GetMapping("/{id}/validation")
    @Operation(summary = "Validate trip", description = "Validate a trip before running optimization")
    public ResponseEntity<ApiResponse<ValidationResponse>> validateTrip(@PathVariable("id") Long id) {
        ValidationResponse result = tripValidationService.validate(id);
        return ResponseEntity.ok(ApiResponse.success("Kết quả kiểm tra chuyến đi", result));
    }
}
