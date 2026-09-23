package fu.se184491.loadmaster_be.service.optimize;

import java.util.concurrent.CompletableFuture;

/**
 * Submits an optimization job for asynchronous execution in the background.
 *
 * <p>The controller calls this immediately after persisting the job (PENDING),
 * returning 202 Accepted to the client. The actual engine call happens in a
 * background thread managed by the {@code optimizationExecutor} pool.
 */
public interface AsyncOptimizationRunner {

    /**
     * Kicks off the optimization pipeline asynchronously.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Build {@link fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest}
     *       from the trip + packages</li>
     *   <li>Delegate to {@link EngineExceptionHandler#executeWithHandling(String, Object)}
     *       — handles all exceptions and updates job status</li>
     * </ol>
     *
     * @param jobUuid the UUID of the PENDING job to execute
     */
    CompletableFuture<Void> runJob(String jobUuid);
}
