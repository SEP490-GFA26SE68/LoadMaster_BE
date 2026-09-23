package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;

/**
 * Sends real-time WebSocket notifications when an optimization job
 * changes status (S3-10).
 *
 * <p>Implementations publish to: {@code /topic/jobs/{jobUuid}}
 */
public interface JobNotificationService {

    /**
     * Notifies all subscribers of a job status change.
     *
     * @param jobUuid   the UUID of the job
     * @param status    the new terminal status
     * @param planId    the id of the created LoadPlan, or {@code null} if no plan was created
     * @param message   human-readable summary for the UI
     */
    void notifyJobStatus(String jobUuid, OptimizationJobStatus status, Long planId, String message);
}
