
package fu.se184491.loadmaster_be.controller.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;


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

    private final fu.se184491.loadmaster_be.service.optimization.OptimizationSubmissionService submissionService;
    private final OptimizationClient optimizationClient;

    @PostMapping("/{tripId}/optimize-loading")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<fu.se184491.loadmaster_be.dto.ApiResponse<Map<String, String>>> optimizeLoading(
            @PathVariable Long tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String suppliedKey
    ) {
        // Optional for compatibility. Callers needing retry guarantees MUST send and retain their own key.
        String key = suppliedKey == null ? java.util.UUID.randomUUID().toString() : suppliedKey;
        var response = submissionService.submitInitial(tripId, key);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Idempotency-Key", key).body(response);
    }

    @GetMapping("/optimization-jobs/{jobId}")
    @PreAuthorize("hasRole('DISPATCHER') and @optimizationAccess.canReadJob(#jobId)")
    public ResponseEntity<String> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobStatus(jobId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/optimization-jobs/{jobId}/plans")
    @PreAuthorize("hasRole('DISPATCHER') and @optimizationAccess.canReadJob(#jobId)")
    public ResponseEntity<String> getOptimizationJobPlans(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobPlans(jobId);

        return ResponseEntity.ok(response);
    }

}
