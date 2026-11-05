
package fu.se184491.loadmaster_be.controller.optimization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.trip.TripService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class LoadingOptimizationController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TripService tripService;
    private final OptimizationClient optimizationClient;

    @PostMapping(
            value = "/{tripId}/optimize-loading",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<Object> optimizeLoading(
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

        // 4. Trả kết quả FastAPI cho Frontend (object JSON, không phải chuỗi escaped).
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(readJson(response));
    }


    @GetMapping(
            value = "/optimization-jobs/{jobId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<Object> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobStatus(jobId);

        return ResponseEntity.ok(readJson(response));
    }

    @GetMapping(
            value = "/optimization-jobs/{jobId}/plans",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<Object> getOptimizationJobPlans(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobPlans(jobId);

        return ResponseEntity.ok(readJson(response));
    }

    private Object readJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid optimization service response",
                    exception
            );
        }
    }

}
