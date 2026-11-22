package fu.se184491.loadmaster_be.service.optimization;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
public final class OptimizationLifecyclePolicy {
    private OptimizationLifecyclePolicy() {}
    public static boolean refundable(OptimizationJob job) {
        if (job.getExecutionGeneration() == null || job.getStatus() == null) return false;
        long generation=job.getExecutionGeneration();
        return switch (job.getStatus()) {
            case NO_SOLUTION, TIMEOUT -> generation > 0 && job.getExecutionStartedAt() != null;
            case FAILED -> switch (job.getFailureCode() == null ? "" : job.getFailureCode()) {
                case "PACKING_PROFILE_ORIENTATION_INVALID" -> generation == 0 && job.getExecutionStartedAt() == null;
                case "ENGINE_EXECUTION_FAILED" -> generation > 0 && job.getExecutionStartedAt() != null;
                case "DISPATCH_EXPIRED" -> generation == 1 && job.getExecutionStartedAt() == null;
                case "LEASE_EXPIRED" -> generation >= 2;
                default -> false;
            };
            default -> false;
        };
    }
}
