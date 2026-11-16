package fu.se184491.loadmaster_be.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;

/** Validate the application acknowledgement, not merely the HTTP status.
 * Older Python versions return only job_id; new fields are additive.
 */
public final class OptimizationDispatchAcknowledgement {
    private OptimizationDispatchAcknowledgement() {}

    public static OptimizationJobStatus verifyV2(String body, String reservedJobId) {
        try {
            var data = new ObjectMapper().readTree(body).path("data");
            var generation = data.path("execution_generation");
            if (!data.path("protocol_version").isIntegralNumber() || !data.path("protocol_version").canConvertToInt() || data.path("protocol_version").intValue() != 2
                    || !data.path("status").isTextual() || !data.path("dispatch_state").isTextual()
                    || !generation.isIntegralNumber() || !generation.canConvertToLong() || generation.longValue() < 0
                    || "PENDING".equals(data.path("status").asText())
                    || ("RUNNING".equals(data.path("status").asText()) && generation.longValue() == 0)) {
                throw new IllegalArgumentException("Missing V2 execution fence");
            }
            return verify(body, reservedJobId);
        } catch (Exception ex) { throw new IllegalStateException("Unconfirmed V2 dispatch", ex); }
    }

    public static OptimizationJobStatus verify(String body, String reservedJobId) {
        try {
            if (body == null || body.isBlank()) throw new IllegalArgumentException("Empty dispatch response");
            var envelope = new ObjectMapper().readTree(body);
            var success = envelope.path("success");
            var data = envelope.path("data");
            var jobId = data.path("job_id");
            if (!success.isBoolean() || !success.booleanValue() || !data.isObject()
                    || !jobId.isTextual() || !reservedJobId.equals(jobId.textValue())) {
                throw new IllegalArgumentException("Dispatch did not confirm the reserved job");
            }
            OptimizationJobStatus acknowledgedStatus = null;
            if (data.has("status") || data.has("dispatch_state")) {
                acknowledgedStatus = OptimizationJobStatus.valueOf(data.path("status").textValue());
                String expected = switch (acknowledgedStatus) {
                    case PENDING -> "ACCEPTED";
                    case RUNNING -> "ALREADY_RUNNING";
                    default -> "ALREADY_TERMINAL";
                };
                if (!expected.equals(data.path("dispatch_state").textValue())) {
                    throw new IllegalArgumentException("Inconsistent dispatch acknowledgement");
                }
            }
            return acknowledgedStatus; // null only for the legacy job_id-only ACK.
        } catch (Exception ex) {
            throw new IllegalStateException("Unconfirmed optimization dispatch", ex);
        }
    }
}
