
package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.trip.TripService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class LoadingOptimizationController {

    private final TripService tripService;
    private final OptimizationClient optimizationClient;

    @PostMapping("/{tripId}/optimize-loading")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<String> optimizeLoading(
            @PathVariable Long tripId
    ) {
        // 1. Kiểm tra Trip có thuộc công ty đang đăng nhập không.
        tripService.getTripById(tripId);

        // 2. Tạo JSON request cho FastAPI.
        Map<String, Object> request = Map.of(
                "trip_id", tripId,
                "objective", "MAX_VOLUME",
                "time_limit_sec", 60
        );

        // 3. Gửi request sang FastAPI bằng service token.
        String response = optimizationClient.createJob(request);

        // 4. Trả kết quả FastAPI cho Frontend.
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
    }


    @GetMapping("/optimization-jobs/{jobId}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<String> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobStatus(jobId);

        return ResponseEntity.ok(response);
    }

}
