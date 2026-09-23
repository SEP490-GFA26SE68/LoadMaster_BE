package fu.se184491.loadmaster_be.dto.websocket;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload pushed to WebSocket subscribers when an optimization job
 * changes status (S3-10).
 *
 * <p>Published to topic: {@code /topic/jobs/{jobUuid}}
 *
 * <p>React client example:
 * <pre>
 *   client.subscribe('/topic/jobs/abc-uuid', msg => {
 *     const notification = JSON.parse(msg.body);
 *     // { jobUuid, status, planId, message }
 *   });
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobStatusNotification {

    /** UUID of the completed/failed job. */
    private String jobUuid;

    /** Terminal status reached by the job. */
    private OptimizationJobStatus status;

    /**
     * ID of the created {@link fu.se184491.loadmaster_be.entity.optimization.LoadPlan}.
     * Null when status is FAILED, TIMEOUT, or NO_SOLUTION.
     */
    private Long planId;

    /**
     * Human-readable summary message for the UI toast/banner.
     * Examples: "Optimization completed (5 packages placed)",
     *           "Engine unreachable — please retry"
     */
    private String message;
}
