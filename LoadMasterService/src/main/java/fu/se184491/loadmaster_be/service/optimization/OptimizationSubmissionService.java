package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.client.OptimizationDispatchAcknowledgement;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptimizationSubmissionService {
    private final OptimizationAdmissionService admission;
    private final OptimizationClient client;

    public ApiResponse<Map<String, String>> submitInitial(Long tripId, String key) {
        // Separate proxied service: transaction commits here before Python can see the job.
        var job = admission.reserve(tripId, null, OptimizationObjective.MAX_VOLUME, 60, null, key);
        String dispatch = "ALREADY_ACCEPTED";
        if (job.getStatus() == OptimizationJobStatus.PENDING) {
            try {
                var response = client.createJob(Map.of("trip_id", tripId, "job_uuid", job.getJobUuid()));
                var acknowledgedStatus = OptimizationDispatchAcknowledgement.verify(response, job.getJobUuid());
                dispatch = acknowledgedStatus == null || acknowledgedStatus == OptimizationJobStatus.PENDING
                        ? "ACCEPTED" : "ALREADY_ACCEPTED";
            } catch (RuntimeException ex) {
                // Response loss is ambiguous: Python may already own RUNNING. Never refund/recreate here.
                // PENDING is durable and can be submitted again with the SAME key.
                log.warn("Dispatch outcome unknown for reserved job {}; retry the same idempotency key", job.getJobUuid());
                dispatch = "UNKNOWN";
            }
        }
        return ApiResponse.success("Optimization action reserved", Map.of(
                "job_id", job.getJobUuid(), "dispatch_status", dispatch));
    }
}
