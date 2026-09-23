package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest;

/**
 * Port (interface) for communicating with the Optimization Engine (FastAPI).
 *
 * <p>Implementations must inject a valid service token on every HTTP call.
 *
 * @see fu.se184491.loadmaster_be.service.optimize.Impl.HttpOptimizationClient
 */
public interface OptimizationClient {

    /**
     * Submits a packing problem to the engine and waits for the result.
     *
     * <p>Maps to: {@code POST {engine-url}/api/v1/optimization/jobs}
     *
     * @param request the fully-built problem description
     * @return the engine's placement solution and metrics
     * @throws fu.se184491.loadmaster_be.exception.ServiceAuthenticationException
     *         if the service token cannot be obtained
     * @throws org.springframework.web.client.RestClientException
     *         on HTTP or network errors from the engine
     */
    OptimizationResult solve(ProblemRequest request);
}
