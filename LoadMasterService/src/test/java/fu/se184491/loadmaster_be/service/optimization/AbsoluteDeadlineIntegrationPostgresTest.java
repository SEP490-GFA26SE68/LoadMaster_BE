package fu.se184491.loadmaster_be.service.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python fenced save + PostgreSQL clock/locks + Spring reconciler. Owned test DB only. */
class AbsoluteDeadlineIntegrationPostgresTest {
    final OrientationIntegrationPostgresTest fixture = new OrientationIntegrationPostgresTest();
    @BeforeAll static void start() throws Exception { JobLifecycleIntegrationPostgresTest.start(); }
    @AfterAll static void stop() throws Exception { JobLifecycleIntegrationPostgresTest.stop(); }
    @BeforeEach void seed() throws Exception {
        fixture.seed();
        OrientationIntegrationPostgresTest.control("POST", "seed?mode=valid");
    }
    String running(String key) {
        String uuid=OptimizationCreditPostgresTest.admission.reserve(7L,null,
            fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective.MAX_VOLUME,60,null,key).getJobUuid();
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET execution_generation=1,status='RUNNING',execution_started_at=clock_timestamp(),lease_expires_at=clock_timestamp()+interval '120 seconds' WHERE job_uuid=?",uuid);
        return uuid;
    }
    JsonNode finalizePlan(String uuid,String mode) throws Exception {
        return OrientationIntegrationPostgresTest.control("POST","deadline-persist?uuid="+uuid+"&mode="+mode);
    }
    void reconcile(String uuid) {
        OptimizationCreditPostgresTest.context.getBean(OptimizationLifecycleReconciler.class).reconcile(uuid);
    }
    void observedRealWrites(JsonNode answer) {
        assertEquals(1,answer.path("flushed_counts").path("load_plans").asInt());
        assertEquals(4,answer.path("flushed_counts").path("package_placements").asInt());
        assertEquals(1,answer.path("flushed_counts").path("unplaced_packages").asInt());
        assertEquals(0,answer.path("refund_calls").asInt());
        assertEquals(0,answer.path("deduct_calls").asInt());
        assertEquals(0,answer.path("blocked_network_attempts").asInt());
    }
    void noPartialRows() {
        for(String table:new String[]{"load_plans","package_placements","unplaced_packages"}) assertEquals(0,fixture.count(table),table);
    }
    void awaitFlush() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            if(OrientationIntegrationPostgresTest.control("GET","deadline-state").path("flushed").asBoolean()) return;
            Thread.sleep(30);
        }
        fail("Python did not reach the real persistence flush");
    }
    void awaitBlockedReconciler(Future<?> task) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            assertFalse(task.isDone(),"Reconciler must wait for the finalize row lock");
            if(Boolean.TRUE.equals(OptimizationCreditPostgresTest.sql.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0)",Boolean.class))) return;
            Thread.sleep(30);
        }
        fail("No PostgreSQL lock wait observed");
    }
    @Test void persistenceBeforeDeadlineCommitsAndPreservesAllPackageIds() throws Exception {
        var answer=finalizePlan(running("deadline-before"),"before");
        assertTrue(answer.path("allowed").asBoolean());observedRealWrites(answer);
        assertEquals("PARTIAL",fixture.status());
        assertEquals(5,OptimizationCreditPostgresTest.sql.queryForObject(
            "SELECT count(DISTINCT package_id) FROM (SELECT package_id FROM package_placements UNION ALL SELECT package_id FROM unplaced_packages) p",Integer.class));
        assertEquals(5,fixture.count("package_placements")+fixture.count("unplaced_packages"));
        fixture.billingSafe();
    }
    @Test void absoluteDeadlineDuringPersistenceRollsBackEveryResultRowAndStatus() throws Exception {
        String uuid=running("deadline-absolute");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET execution_started_at=clock_timestamp()-interval '55 seconds' WHERE job_uuid=?",uuid);
        var answer=finalizePlan(uuid,"absolute");
        assertFalse(answer.path("allowed").asBoolean());observedRealWrites(answer);
        noPartialRows();assertEquals("RUNNING",fixture.status());fixture.billingSafe();
        assertEquals(1L,OptimizationCreditPostgresTest.sql.queryForObject("SELECT execution_generation FROM optimization_jobs",Long.class));
    }
    @Test void leaseExpiryDuringPersistenceAlsoRollsBackEveryResultRow() throws Exception {
        String uuid=running("deadline-lease");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()+interval '5 seconds' WHERE job_uuid=?",uuid);
        var answer=finalizePlan(uuid,"lease");
        assertFalse(answer.path("allowed").asBoolean());observedRealWrites(answer);
        noPartialRows();assertEquals("RUNNING",fixture.status());fixture.billingSafe();
    }
    void race(boolean expireLease) throws Exception {
        String uuid=running(expireLease?"deadline-race-expiry":"deadline-race-valid");
        if(expireLease) OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()+interval '5 seconds' WHERE job_uuid=?",uuid);
        var pool=Executors.newFixedThreadPool(2);
        Future<JsonNode> writer=pool.submit(()->finalizePlan(uuid,expireLease?"hold-lease":"hold"));
        try {
            awaitFlush();
            // Uncommitted savepoint rows must be invisible from this independent JDBC connection.
            noPartialRows();assertEquals("RUNNING",fixture.status());
            Future<?> recovery=pool.submit(()->reconcile(uuid));
            awaitBlockedReconciler(recovery);
            OrientationIntegrationPostgresTest.control("POST","deadline-release");
            var answer=writer.get(15,TimeUnit.SECONDS);recovery.get(15,TimeUnit.SECONDS);
            observedRealWrites(answer);
            assertEquals(!expireLease,answer.path("allowed").asBoolean());
            if(expireLease) {
                noPartialRows();assertEquals("FAILED",fixture.status());
                assertEquals("LEASE_EXPIRED",OptimizationCreditPostgresTest.sql.queryForObject("SELECT failure_code FROM optimization_jobs",String.class));
                assertEquals(2L,OptimizationCreditPostgresTest.sql.queryForObject("SELECT execution_generation FROM optimization_jobs",Long.class));
                assertEquals(100,OptimizationCreditPostgresTest.sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
                assertEquals(1,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
                assertEquals(2,OptimizationCreditPostgresTest.sql.queryForObject("SELECT amount FROM credit_transactions WHERE type='REFUND'",Integer.class));
                reconcile(uuid);
                assertFalse(finalizePlan(uuid,"before").path("allowed").asBoolean());
                noPartialRows();
                assertEquals(1,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
            } else {
                assertEquals("PARTIAL",fixture.status());assertEquals(1,fixture.count("load_plans"));fixture.billingSafe();
            }
        } finally {
            OrientationIntegrationPostgresTest.control("POST","deadline-release");
            pool.shutdown();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS),"Owned concurrent test tasks did not terminate");
        }
    }
    @Test void reconcilerWaitsForSuccessfulFinalizeWithoutRefund() throws Exception {race(false);}
    @Test void reconcilerAfterExpiredFinalizeRollbackRevokesAndRefundsOnlyOnce() throws Exception {race(true);}
    @Test void reconcilerWinningFirstPreventsLateWorkerFromWritingAnything() throws Exception {
        String uuid=running("deadline-recovery-first");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_uuid=?",uuid);
        reconcile(uuid);
        var answer=finalizePlan(uuid,"before");
        assertFalse(answer.path("allowed").asBoolean());assertTrue(answer.path("flushed_counts").isEmpty());
        noPartialRows();assertEquals("FAILED",fixture.status());
        assertEquals(100,OptimizationCreditPostgresTest.sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(1,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
        assertEquals(0,answer.path("refund_calls").asInt());assertEquals(0,answer.path("deduct_calls").asInt());
    }
}
