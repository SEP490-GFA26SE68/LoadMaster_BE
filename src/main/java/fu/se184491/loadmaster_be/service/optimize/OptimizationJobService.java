package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.OptimizationJobRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobResponse;

import java.util.List;

public interface OptimizationJobService {

    /**
     * Creates an OptimizationJob with PENDING status and a generated UUID.
     *
     * @param request job configuration (tripId, objective, timeLimitSec, seed)
     * @return persisted job response
     * @throws fu.se184491.loadmaster_be.exception.AppException TRIP_NOT_FOUND if trip does not exist
     */
    OptimizationJobResponse createJob(OptimizationJobRequest request);

    /**
     * Returns the current status and metadata for a job.
     *
     * @param jobUuid UUID of the job
     * @return job response with status, computationMs, etc.
     * @throws fu.se184491.loadmaster_be.exception.AppException OPTIMIZATION_JOB_NOT_FOUND
     */
    OptimizationJobResponse getJob(String jobUuid);

    /**
     * Returns all jobs for a given trip, ordered by creation (implicit).
     */
    List<OptimizationJobResponse> findByTripId(Long tripId);

    /**
     * Returns all jobs with the given status.
     */
    List<OptimizationJobResponse> findByStatus(OptimizationJobStatus status);

    /**
     * Updates the status (and optionally computationMs) of an existing job.
     *
     * @param jobUuid       UUID of the job to update
     * @param status        new status to set
     * @param computationMs elapsed milliseconds (may be null for FAILED/TIMEOUT)
     * @throws fu.se184491.loadmaster_be.exception.AppException OPTIMIZATION_JOB_NOT_FOUND
     */
    void updateStatus(String jobUuid, OptimizationJobStatus status, Long computationMs);
}
