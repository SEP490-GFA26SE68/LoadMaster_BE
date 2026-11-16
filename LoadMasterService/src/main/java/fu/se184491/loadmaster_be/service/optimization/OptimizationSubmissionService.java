package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.client.OptimizationDispatchRejectedException;
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
        long dispatchStarted = System.nanoTime();
        String dispatch = "ALREADY_ACCEPTED";
        if (job.getStatus() == OptimizationJobStatus.PENDING) {
            log.info("optimization_submission job_uuid={} trip_id={} stage=dispatch_started", job.getJobUuid(), tripId);
            try {
                var request = new java.util.HashMap<String, Object>();
                request.put("trip_id", tripId); request.put("job_uuid", job.getJobUuid());
                boolean v2 = job.getExecutionGeneration() != null;
                if (v2) request.put("protocol_version", 2);
                var response = client.createJob(request);
                var acknowledgedStatus = v2 ? OptimizationDispatchAcknowledgement.verifyV2(response, job.getJobUuid())
                        : OptimizationDispatchAcknowledgement.verify(response, job.getJobUuid());
                dispatch = acknowledgedStatus == null || acknowledgedStatus == OptimizationJobStatus.PENDING
                        ? "ACCEPTED" : "ALREADY_ACCEPTED";
            } catch (OptimizationDispatchRejectedException ex) {
                // Only this attempt was rejected. A concurrent ACK may still be waiting to claim PENDING.
                // Preserve the committed job/ledger and existing AppException HTTP contract; do not refund.
                log.warn("optimization_submission job_uuid={} trip_id={} stage=dispatch_rejected error_code={}",
                        job.getJobUuid(), tripId, ex.getErrorCode().name());
                // This identifies the reserved action, not a promise that remote terminal commit/refund happened.
                ex.setDetails(Map.of("job_id", job.getJobUuid(), "trip_id", tripId));
                throw ex;
            } catch (RuntimeException ex) {
                // Response loss is ambiguous: Python may already own RUNNING. Never refund/recreate here.
                // PENDING is durable and can be submitted again with the SAME key.
                log.warn("optimization_submission job_uuid={} trip_id={} stage=dispatch_unknown exception_type={}",
                        job.getJobUuid(), tripId, ex.getClass().getSimpleName());
                dispatch = "UNKNOWN";
            }
        }
        log.info("optimization_submission job_uuid={} trip_id={} stage=dispatch_finished dispatch_status={} elapsed_ms={}",
                job.getJobUuid(), tripId, dispatch, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dispatchStarted));
        return ApiResponse.success("Optimization action reserved", Map.of(
                "job_id", job.getJobUuid(), "dispatch_status", dispatch));
    }
}
