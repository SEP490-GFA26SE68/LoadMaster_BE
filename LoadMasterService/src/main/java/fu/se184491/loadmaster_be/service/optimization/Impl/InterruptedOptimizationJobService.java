package fu.se184491.loadmaster_be.service.optimization.Impl;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
/** Compatibility seam only. Legacy ownership is unknown; GET must not fail/refund by age.
 * V2 recovery runs through OptimizationLifecycleReconciler using the canonical lease/fence. */
@Service
public class InterruptedOptimizationJobService {
    public static final String FAILURE_CODE = "JOB_INTERRUPTED";
    public InterruptedOptimizationJobService(LoadPlanWriteRepository writes, CreditService credits,
            @Value("${app.optimization.interrupted-job-grace-seconds:300}") long ignoredLegacyGrace) {}
    public boolean failIfInterrupted(JobOwner owner, OptimizationJobStatusResponse status, Instant now) {
        return false;
    }
}
