package fu.se184491.loadmaster_be.service.optimization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="app.optimization.lifecycle-v2.enabled", havingValue="true")
public class OptimizationLifecycleScheduler {
    private final JdbcTemplate jdbc;
    private final OptimizationLifecycleReconciler reconciler;
    @Scheduled(fixedDelayString="${app.optimization.lifecycle-v2.reconcile-delay-ms:10000}")
    public void reconcile() {
        var uuids = jdbc.queryForList("""
            SELECT j.job_uuid FROM optimization_jobs j
            WHERE j.execution_generation IS NOT NULL AND (
              (j.status='PENDING' AND j.dispatch_expires_at <= clock_timestamp()) OR
              (j.status='RUNNING' AND j.lease_expires_at <= clock_timestamp()) OR
              (((j.status IN ('TIMEOUT','NO_SOLUTION') AND j.execution_generation>0 AND j.execution_started_at IS NOT NULL)
                OR (j.status='FAILED' AND (
                  (j.failure_code='PACKING_PROFILE_ORIENTATION_INVALID' AND j.execution_generation=0 AND j.execution_started_at IS NULL)
                  OR (j.failure_code='ENGINE_EXECUTION_FAILED' AND j.execution_generation>0 AND j.execution_started_at IS NOT NULL)
                  OR (j.failure_code='DISPATCH_EXPIRED' AND j.execution_generation=1 AND j.execution_started_at IS NULL)
                  OR (j.failure_code='LEASE_EXPIRED' AND j.execution_generation>=2)))) AND EXISTS (
                SELECT 1 FROM credit_transactions t WHERE t.reference=j.job_uuid AND t.type='USAGE' AND t.refunded=false)))
            ORDER BY j.id LIMIT 100
            """, String.class);
        for (String uuid : uuids) {
            try { reconciler.reconcile(uuid); }
            catch (RuntimeException ex) { log.warn("optimization_reconcile job_uuid={} exception_type={}", uuid, ex.getClass().getSimpleName()); }
        }
    }
}
