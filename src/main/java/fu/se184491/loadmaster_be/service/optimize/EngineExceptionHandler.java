package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest;
import fu.se184491.loadmaster_be.exception.ServiceAuthenticationException;

/**
 * Orchestrates a single optimization attempt, handling all engine exceptions.
 *
 * <p>The handler is responsible for:
 * <ol>
 *   <li>Calling {@link OptimizationClient#solve(ProblemRequest)}</li>
 *   <li>Classifying the result (COMPLETED / NO_SOLUTION / PARTIAL)</li>
 *   <li>On exception: mapping it to the right {@link OptimizationJobStatus} and logging</li>
 *   <li>Updating job status via {@link OptimizationJobService} in all branches</li>
 * </ol>
 */
public interface EngineExceptionHandler {

    /**
     * Executes the optimization call and handles all error cases.
     *
     * <p>Possible job status outcomes (S3-07):
     * <ul>
     *   <li>{@code COMPLETED} — all packages placed</li>
     *   <li>{@code PARTIAL}   — some packages unplaced</li>
     *   <li>{@code NO_SOLUTION} — zero packages placed</li>
     *   <li>{@code FAILED}   — engine unreachable, auth error, or HTTP 4xx/5xx</li>
     *   <li>{@code TIMEOUT}  — engine exceeded time limit</li>
     * </ul>
     *
     * @param jobUuid the UUID of the OptimizationJob to update on failure
     * @param request the problem to send to the engine
     * @return the {@link OptimizationResult} on success (never null on success)
     * @throws RuntimeException only re-throws unexpected errors after updating job status
     */
    OptimizationResult executeWithHandling(String jobUuid, ProblemRequest request);
}
