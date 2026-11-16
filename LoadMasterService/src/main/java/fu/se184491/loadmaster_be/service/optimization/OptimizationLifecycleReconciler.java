package fu.se184491.loadmaster_be.service.optimization;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Service
@RequiredArgsConstructor
public class OptimizationLifecycleReconciler {
    private final OptimizationJobRepository jobs;
    private final CreditService credits;
    @jakarta.persistence.PersistenceContext private EntityManager entityManager;
    @Transactional
    public void reconcile(String uuid) {
        var job = jobs.findByJobUuidForUpdate(uuid).orElse(null);
        if (job == null) return;
        entityManager.refresh(job);
        if (job.getExecutionGeneration() == null) return;
        Instant now = (Instant) entityManager.createNativeQuery("select clock_timestamp()", Instant.class).getSingleResult();
        String expired = null;
        if (job.getStatus() == OptimizationJobStatus.PENDING && job.getExecutionGeneration() == 0
                && job.getDispatchExpiresAt() != null && !job.getDispatchExpiresAt().isAfter(now)) expired = "DISPATCH_EXPIRED";
        if (job.getStatus() == OptimizationJobStatus.RUNNING && job.getLeaseExpiresAt() != null
                && !job.getLeaseExpiresAt().isAfter(now)) expired = "LEASE_EXPIRED";
        if (expired != null) {
            job.setExecutionGeneration(Math.addExact(job.getExecutionGeneration(), 1));
            job.setStatus(OptimizationJobStatus.FAILED);
            job.setFailureCode(expired);
            entityManager.flush();
        }
        if (OptimizationLifecyclePolicy.refundable(job)) credits.refundCredit(Long.valueOf(job.getCompanyId()), uuid);
    }
}
