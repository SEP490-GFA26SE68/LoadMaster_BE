package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest;
import fu.se184491.loadmaster_be.exception.ServiceAuthenticationException;
import fu.se184491.loadmaster_be.service.optimize.EngineExceptionHandler;
import fu.se184491.loadmaster_be.service.optimize.JobNotificationService;
import fu.se184491.loadmaster_be.service.optimize.OptimizationClient;
import fu.se184491.loadmaster_be.service.optimize.OptimizationJobService;
import fu.se184491.loadmaster_be.service.optimize.OptimizationResultPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Default implementation of {@link EngineExceptionHandler}.
 *
 * <p>Exception → Status mapping (AC S3-07):
 * <pre>
 *   ResourceAccessException (connection refused)  → FAILED    (SERVICE_UNAVAILABLE)
 *   SocketTimeoutException                         → TIMEOUT   (time limit exceeded)
 *   HttpClientErrorException 401                   → FAILED    (AUTH_ERROR)
 *   HttpClientErrorException 403                   → FAILED    (AUTH_FORBIDDEN — missing scope)
 *   ServiceAuthenticationException (no token)      → FAILED    (AUTH_ERROR)
 *   Any other RestClientException                  → FAILED    (engine error)
 *   Result: 0 placements                           → NO_SOLUTION
 *   Result: some unplaced                          → PARTIAL
 *   Result: all placed                             → COMPLETED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngineExceptionHandlerImpl implements EngineExceptionHandler {

    private final OptimizationClient optimizationClient;
    private final OptimizationJobService optimizationJobService;
    private final OptimizationResultPersistenceService persistenceService;
    private final JobNotificationService jobNotificationService;

    @Override
    public OptimizationResult executeWithHandling(String jobUuid, ProblemRequest request) {
        long startMs = System.currentTimeMillis();

        try {
            // Mark RUNNING
            optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.RUNNING, null);

            OptimizationResult result = optimizationClient.solve(request);

            // Classify result to determine final status
            OptimizationJobStatus finalStatus = classifyResult(result);

            if (finalStatus == OptimizationJobStatus.NO_SOLUTION) {
                // 0 placements — update status only, no LoadPlan to save
                long computationMs = System.currentTimeMillis() - startMs;
                optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.NO_SOLUTION, computationMs);
                log.warn("[job={}] Engine returned 0 placements — NO_SOLUTION. Elapsed: {}ms",
                        jobUuid, computationMs);
                // Notify WebSocket subscribers (S3-10)
                jobNotificationService.notifyJobStatus(
                        jobUuid, OptimizationJobStatus.NO_SOLUTION, null,
                        "No solution found — all packages could not be placed");
            } else {
                // COMPLETED or PARTIAL — persist plan, placements, unplaced + update job status
                persistenceService.persist(jobUuid, result);
                long computationMs = System.currentTimeMillis() - startMs;
                if (finalStatus == OptimizationJobStatus.PARTIAL) {
                    int unplacedCount = result.getUnplaced() != null ? result.getUnplaced().size() : 0;
                    log.warn("[job={}] PARTIAL solution: {} package(s) unplaced. Elapsed: {}ms",
                            jobUuid, unplacedCount, computationMs);
                } else {
                    log.info("[job={}] COMPLETED. Elapsed: {}ms", jobUuid, computationMs);
                }
            }

            return result;

        } catch (ServiceAuthenticationException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[job={}] AUTH_ERROR — could not obtain service token: {}", jobUuid, e.getMessage());
            optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
            jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                    "Authentication error — could not reach engine");
            throw e;

        } catch (HttpClientErrorException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.error("[job={}] AUTH_ERROR — engine returned 401 (token missing/expired/wrong audience): {}",
                        jobUuid, e.getMessage());
                optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
                jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                        "Engine authentication failed (401)");
                throw new ServiceAuthenticationException(
                        "Engine service authentication failed (401): " + e.getMessage(), e);
            }
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.error("[job={}] AUTH_FORBIDDEN — engine returned 403 (insufficient scope 'optimization.execute'): {}",
                        jobUuid, e.getMessage());
                optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
                jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                        "Engine authorization failed (403 — insufficient scope)");
                throw new ServiceAuthenticationException(
                        "Engine authorization failed (403 — insufficient scope): " + e.getMessage(), e);
            }
            // Other 4xx/5xx
            log.error("[job={}] Engine HTTP error {}: {}", jobUuid, e.getStatusCode(), e.getMessage());
            optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
            jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                    "Engine error: " + e.getStatusCode());
            throw e;

        } catch (ResourceAccessException e) {
            // Connection refused, network unreachable
            long elapsed = System.currentTimeMillis() - startMs;
            if (isTimeout(e)) {
                log.error("[job={}] TIMEOUT — engine exceeded time limit: {}", jobUuid, e.getMessage());
                optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.TIMEOUT, elapsed);
                jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.TIMEOUT, null,
                        "Engine timeout — exceeded time limit");
            } else {
                log.error("[job={}] SERVICE_UNAVAILABLE — engine unreachable: {}", jobUuid, e.getMessage());
                optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
                jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                        "Engine unreachable — please retry later");
            }
            throw e;

        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[job={}] Engine call failed: {}", jobUuid, e.getMessage(), e);
            optimizationJobService.updateStatus(jobUuid, OptimizationJobStatus.FAILED, elapsed);
            jobNotificationService.notifyJobStatus(jobUuid, OptimizationJobStatus.FAILED, null,
                    "Engine error — unexpected failure");
            throw e;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Classifies a successful engine response into the terminal job status.
     */
    public static OptimizationJobStatus classifyResult(OptimizationResult result) {
        List<?> placements = result.getPlacements();
        List<?> unplaced   = result.getUnplaced();

        boolean hasPlaced   = placements != null && !placements.isEmpty();
        boolean hasUnplaced = unplaced   != null && !unplaced.isEmpty();

        if (!hasPlaced)              return OptimizationJobStatus.NO_SOLUTION;
        if (hasUnplaced)             return OptimizationJobStatus.PARTIAL;
        return OptimizationJobStatus.COMPLETED;
    }

    /**
     * Heuristic: ResourceAccessException wrapping a SocketTimeoutException is a timeout.
     */
    private boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return cause != null && (
                cause instanceof java.net.SocketTimeoutException
                || cause.getClass().getSimpleName().contains("Timeout"));
    }
}
