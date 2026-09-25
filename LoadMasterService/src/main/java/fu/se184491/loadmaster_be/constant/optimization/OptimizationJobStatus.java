package fu.se184491.loadmaster_be.constant.optimization;

public enum OptimizationJobStatus {
    /** Job queued, waiting to be picked up */
    PENDING,
    /** Job is currently being processed by the engine */
    RUNNING,
    /** All packages placed successfully */
    COMPLETED,
    /** Kept for backward compatibility — prefer COMPLETED */
    SUCCESS,
    /** Engine call failed or Spring could not reach the engine */
    FAILED,
    /** Engine exceeded the configured time limit */
    TIMEOUT,
    /** Engine found no valid placement for any package */
    NO_SOLUTION,
    /** Some packages were placed, some were not */
    PARTIAL
}
