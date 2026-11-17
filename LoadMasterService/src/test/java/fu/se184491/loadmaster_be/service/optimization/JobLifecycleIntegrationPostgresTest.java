package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.support.TestSqlFixtures;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses only the owned PostgreSQL + local Python HTTP fixture, never application profiles. */
class JobLifecycleIntegrationPostgresTest {
    final OrientationIntegrationPostgresTest fixture = new OrientationIntegrationPostgresTest();
    @BeforeAll static void start() throws Exception { System.setProperty("app.optimization.lifecycle-v2.enabled","true");
        System.setProperty("orientation.evidence",System.getProperty("orientation.evidence","docs/reports/evidence/job-lifecycle"));
        OrientationIntegrationPostgresTest.start();
        OrientationIntegrationPostgresTest.control("POST","enable-v2"); }
    @AfterAll static void stop() throws Exception { OrientationIntegrationPostgresTest.stop(); System.clearProperty("app.optimization.lifecycle-v2.enabled"); System.clearProperty("orientation.evidence"); }
    @BeforeEach void seed() throws Exception { fixture.seed(); }
    @Test void invalidProfileHasDurableTerminalDecisionAndOneDebitOnRetry() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=invalid");
        fixture.rejected("lifecycle-invalid");
        assertEquals("FAILED",fixture.status(),"Pre-ACK rejection must be a durable terminal decision");
        var retry=fixture.submit("lifecycle-invalid");
        assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));
        fixture.billingSafe(); // Policy not approved: no implicit refund.
    }
    @Test void ackAlreadyHasDurableOwnerBeforeWorkerBegins() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=true");
        try {
            fixture.submit("lifecycle-owner");
            assertEquals("RUNNING",fixture.status(),"ACK must follow ownership commit");
            var sql=OptimizationCreditPostgresTest.sql;
            assertEquals(1L,sql.queryForObject("SELECT execution_generation FROM optimization_jobs",Long.class));
            assertNull(sql.queryForObject("SELECT execution_started_at FROM optimization_jobs",Object.class));
            var retry=fixture.submit("lifecycle-owner");
            assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));
            fixture.billingSafe();
        } finally { OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=false"); OrientationIntegrationPostgresTest.control("GET","await-finished"); }
        fixture.awaitCompleted();
    }
    @Test void validFiveStillPersistOnceAndConservePackages() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        var first=fixture.submit("lifecycle-five"); fixture.awaitCompleted();
        assertEquals("COMPLETED",fixture.status());
        assertEquals(5,fixture.count("package_placements"));
        assertEquals(1,fixture.count("load_plans"));
        assertEquals(5,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(DISTINCT package_id) FROM package_placements",Integer.class));
        assertEquals(first.get("job_id"),fixture.submit("lifecycle-five").get("job_id"));
        fixture.billingSafe();
    }

    String reserveOnly(String key) {
        return OptimizationCreditPostgresTest.admission.reserve(7L,null,
            fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective.MAX_VOLUME,60,null,key).getJobUuid();
    }
    String runningFixture(String key) {
        String uuid=reserveOnly(key);
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET execution_generation=1,status='RUNNING',lease_expires_at=clock_timestamp()+interval '30 seconds' WHERE job_uuid=?",uuid);
        return uuid;
    }
    void reconcile(String uuid) { OptimizationCreditPostgresTest.context.getBean(OptimizationLifecycleReconciler.class).reconcile(uuid); }
    void refundedOnce() {
        var sql=OptimizationCreditPostgresTest.sql;
        assertEquals(100,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='USAGE'",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
        assertEquals(2,sql.queryForObject("SELECT amount FROM credit_transactions WHERE type='REFUND'",Integer.class));
    }
    com.fasterxml.jackson.databind.JsonNode operation(String uuid,int generation,String op) throws Exception {
        return OrientationIntegrationPostgresTest.control("POST","owner-check?uuid="+uuid+"&generation="+generation+"&operation="+op);
    }
    @Test void crashAfterOwnerCommitBeforeScheduleExpiresAndOldOwnerCannotFinalize() throws Exception {
        String uuid=runningFixture("crash-before-schedule");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_uuid=?",uuid);
        reconcile(uuid);
        assertEquals("FAILED",fixture.status());
        assertFalse(operation(uuid,1,"begin").path("allowed").asBoolean());
        assertFalse(operation(uuid,1,"finalize").path("allowed").asBoolean());
        assertEquals(0,fixture.count("load_plans"));refundedOnce();
        assertEquals(uuid,fixture.submit("crash-before-schedule").get("job_id"));refundedOnce();
    }
    @Test void pendingDispatchExpiryRevokesGenerationBeforeRefund() {
        String uuid=reserveOnly("dispatch-expiry");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET dispatch_expires_at=clock_timestamp()-interval '1 second' WHERE job_uuid=?",uuid);
        reconcile(uuid);reconcile(uuid);
        assertEquals("FAILED",fixture.status());
        assertEquals(1L,OptimizationCreditPostgresTest.sql.queryForObject("SELECT execution_generation FROM optimization_jobs",Long.class));
        refundedOnce();
    }
    @Test void heartbeatAgainstRecoveryKeepsLiveOwnerAndNoRefund() throws Exception {
        String uuid=runningFixture("heartbeat-race");assertTrue(operation(uuid,1,"begin").path("allowed").asBoolean());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{ready.countDown();go.await();return operation(uuid,1,"heartbeat");});
            var b=pool.submit(()->{ready.countDown();go.await();reconcile(uuid);return true;});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            assertTrue(a.get(15,java.util.concurrent.TimeUnit.SECONDS).path("allowed").asBoolean());assertTrue(b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("RUNNING",fixture.status());fixture.billingSafe();
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test void expiredHeartbeatCannotReviveLeaseAndLateCallbackCannotRefundRunning() throws Exception {
        String uuid=runningFixture("late");operation(uuid,1,"begin");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->OptimizationCreditPostgresTest.credits.refundCredit(1L,uuid,1L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->OptimizationCreditPostgresTest.credits.refundCredit(1L,uuid,null));
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_uuid=?",uuid);
        assertFalse(operation(uuid,1,"heartbeat").path("allowed").asBoolean());
        assertFalse(operation(uuid,1,"finalize").path("allowed").asBoolean());
        reconcile(uuid);refundedOnce();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->OptimizationCreditPostgresTest.credits.refundCredit(1L,uuid,1L));
        refundedOnce();
    }
    @Test void duplicateBeginAndRollbackAfterPlanFlushAreSafe() throws Exception {
        String uuid=runningFixture("atomic");
        assertTrue(operation(uuid,1,"begin").path("allowed").asBoolean());
        assertFalse(operation(uuid,1,"begin").path("allowed").asBoolean());
        assertTrue(operation(uuid,1,"rollback").path("rolled_back").asBoolean());
        assertEquals("RUNNING",fixture.status());assertEquals(0,fixture.count("load_plans"));fixture.billingSafe();
    }
    @Test void duplicateDispatchUsesSnapshotDespiteProfileChangeAndOnlyOneWorker() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=true");
        try {
            var first=fixture.submit("snapshot");
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='INVALID' WHERE id=1");
            assertEquals(first.get("job_id"),fixture.submit("snapshot").get("job_id"));
            assertEquals("RUNNING",fixture.status());fixture.billingSafe();
        } finally {
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='0,2' WHERE id=1");
            OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=false");
            OrientationIntegrationPostgresTest.control("GET","await-finished");
        }
        var state=fixture.awaitCompleted();assertEquals(1,state.path("solve_calls").asInt());
    }
    @Test void concurrentSameKeyDispatchHasOneUsageAndWorker() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=true");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Map<String,String>> action=()->{ready.countDown();assertTrue(go.await(5,java.util.concurrent.TimeUnit.SECONDS));return fixture.submit("concurrent-v2");};
            var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            assertEquals(a.get(15,java.util.concurrent.TimeUnit.SECONDS).get("job_id"),b.get(15,java.util.concurrent.TimeUnit.SECONDS).get("job_id"));
            assertEquals("RUNNING",fixture.status());fixture.billingSafe();
        } finally {go.countDown();pool.shutdownNow();OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=false");OrientationIntegrationPostgresTest.control("GET","await-finished");}
        assertEquals(1,fixture.awaitCompleted().path("solve_calls").asInt());
    }
    @Test void invalidTerminalConcurrentRefundsAreOnceAndActualDebit() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=invalid");String uuid=fixture.rejected("invalid-settlement").get("job_id");
        assertEquals("FAILED",fixture.status());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> action=()->{ready.countDown();go.await();reconcile(uuid);return true;};
            var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);
        } finally {go.countDown();pool.shutdownNow();}
        refundedOnce();assertEquals(uuid,fixture.submit("invalid-settlement").get("job_id"));refundedOnce();
    }
    @Test void legacyUnknownOwnershipNeverExpiresOrRefundsAutomatically() {
        String uuid=reserveOnly("legacy");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET execution_generation=NULL,dispatch_expires_at=NULL WHERE job_uuid=?",uuid);
        reconcile(uuid);assertEquals("PENDING",fixture.status());fixture.billingSafe();
    }

    @Test void finalizeAgainstRecoveryHasSingleTerminalAndNoSpuriousRefund() throws Exception {
        String uuid=runningFixture("finalize-race");operation(uuid,1,"begin");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{ready.countDown();go.await();return operation(uuid,1,"finalize");});
            var b=pool.submit(()->{ready.countDown();go.await();reconcile(uuid);return true;});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            assertTrue(a.get(15,java.util.concurrent.TimeUnit.SECONDS).path("allowed").asBoolean());b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("COMPLETED",fixture.status());fixture.billingSafe();
            assertFalse(operation(uuid,1,"finalize").path("allowed").asBoolean());
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test void lossOfAckAfterV2OwnershipKeepsSameUuidAndOneCharge() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        var lossy=org.mockito.Mockito.mock(fu.se184491.loadmaster_be.client.OptimizationClient.class);
        org.mockito.Mockito.when(lossy.createJob(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            OrientationIntegrationPostgresTest.actualClient.createJob(call.getArgument(0));
            throw new org.springframework.web.client.ResourceAccessException("Synthetic ACK loss");
        });
        var unknown=new OptimizationSubmissionService(OptimizationCreditPostgresTest.admission,lossy).submitInitial(7L,"v2-ack-loss").getData();
        assertEquals("UNKNOWN",unknown.get("dispatch_status"));
        OrientationIntegrationPostgresTest.control("GET","await-finished");fixture.awaitCompleted();
        assertEquals(unknown.get("job_id"),fixture.submit("v2-ack-loss").get("job_id"));fixture.billingSafe();
    }
    @Test void zeroUsageSettlementDoesNotMintNominalRefund() {
        String uuid=reserveOnly("zero");
        var sql=OptimizationCreditPostgresTest.sql;
        // Synthetic unlimited fixture: restore its account to the starting amount and set actual USAGE=0.
        sql.update("UPDATE credit_transactions SET amount=0 WHERE reference=?",uuid);
        sql.update("UPDATE credit_accounts SET balance=100");
        sql.update("UPDATE optimization_jobs SET status='FAILED',failure_code='PACKING_PROFILE_ORIENTATION_INVALID' WHERE job_uuid=?",uuid);
        reconcile(uuid);reconcile(uuid);
        assertEquals(100,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
        assertEquals(true,sql.queryForObject("SELECT refunded FROM credit_transactions WHERE type='USAGE'",Boolean.class));
    }
    @Test void metadataProposalIsRerunnableAndPreservesLegacyRows() throws Exception {
        String uuid=reserveOnly("schema-legacy");var sql=OptimizationCreditPostgresTest.sql;
        sql.update("UPDATE optimization_jobs SET execution_generation=NULL,dispatch_expires_at=NULL WHERE job_uuid=?",uuid);
        String script=TestSqlFixtures.read("proposed-optimization-lifecycle-v2.sql");
        sql.execute(script);sql.execute(script);
        assertNull(sql.queryForObject("SELECT execution_generation FROM optimization_jobs",Long.class));
        assertEquals(4,sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='optimization_jobs' AND column_name IN ('execution_generation','dispatch_expires_at','lease_expires_at','execution_started_at') AND is_nullable='YES'",Integer.class));
        fixture.billingSafe();
    }

    @Test void legacyRecoverySqlCannotMutateFencedJob() {
        String uuid=runningFixture("legacy-recovery-fence");
        var sql=OptimizationCreditPostgresTest.sql;
        var writes=new fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository(
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(OptimizationCreditPostgresTest.context.getBean(javax.sql.DataSource.class)));
        Long id=sql.queryForObject("SELECT id FROM optimization_jobs WHERE job_uuid=?",Long.class,uuid);
        assertEquals(0,writes.failUnfinishedJob(id,"JOB_INTERRUPTED"));
        assertEquals("RUNNING",fixture.status());fixture.billingSafe();
    }

    @Test void creditProjectionUsesCanonicalEligibilityAndLedger() {
        String uuid=reserveOnly("projection");var sql=OptimizationCreditPostgresTest.sql;
        var read=new fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository(
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(OptimizationCreditPostgresTest.context.getBean(javax.sql.DataSource.class)));
        Long id=sql.queryForObject("SELECT id FROM optimization_jobs WHERE job_uuid=?",Long.class,uuid);
        assertEquals("CHARGED",read.findJobStatus(id).orElseThrow().getCreditStatus());
        sql.update("UPDATE optimization_jobs SET status='FAILED',failure_code='UNRECOGNIZED' WHERE job_uuid=?",uuid);
        assertEquals("CHARGED",read.findJobStatus(id).orElseThrow().getCreditStatus());
        sql.update("UPDATE optimization_jobs SET failure_code='PACKING_PROFILE_ORIENTATION_INVALID' WHERE job_uuid=?",uuid);
        assertEquals("REFUND_PENDING",read.findJobStatus(id).orElseThrow().getCreditStatus());
        new OptimizationLifecycleScheduler(new org.springframework.jdbc.core.JdbcTemplate(
            OptimizationCreditPostgresTest.context.getBean(javax.sql.DataSource.class)),
            OptimizationCreditPostgresTest.context.getBean(OptimizationLifecycleReconciler.class)).reconcile();
        assertEquals("REFUNDED",read.findJobStatus(id).orElseThrow().getCreditStatus());refundedOnce();
    }

    @Test void twoActualHttpDispatchesSameUuidScheduleOnlyOneWorker() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");String uuid=reserveOnly("direct-duplicate");
        OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=true");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> post=()->{ready.countDown();assertTrue(go.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return OrientationIntegrationPostgresTest.actualClient.createJob(Map.of("trip_id",7,"job_uuid",uuid,"protocol_version",2));};
            var a=pool.submit(post);var b=pool.submit(post);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            for(String response:List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                assertEquals(fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus.RUNNING,
                    fu.se184491.loadmaster_be.client.OptimizationDispatchAcknowledgement.verifyV2(response,uuid));
            assertEquals(2,OrientationIntegrationPostgresTest.control("GET","state").path("http").size());fixture.billingSafe();
        } finally {go.countDown();pool.shutdownNow();OrientationIntegrationPostgresTest.control("POST","gate?phase=worker&hold=false");OrientationIntegrationPostgresTest.control("GET","await-finished");}
        assertEquals(1,fixture.awaitCompleted().path("solve_calls").asInt());
    }

    @Test void injectedCrashAfterActualOwnershipCommitBeforeSchedulingReturnsUnknownSafely() throws Exception {
        OrientationIntegrationPostgresTest.control("POST","seed?mode=valid");
        OrientationIntegrationPostgresTest.control("POST","fail-after-prepare");
        var result=fixture.submit("crash-after-real-commit");String uuid=result.get("job_id");
        assertEquals("UNKNOWN",result.get("dispatch_status"));assertEquals("RUNNING",fixture.status());fixture.billingSafe();
        assertNull(OptimizationCreditPostgresTest.sql.queryForObject("SELECT execution_started_at FROM optimization_jobs",Object.class));
        assertEquals(0,OrientationIntegrationPostgresTest.control("GET","state").path("run_calls").asInt());
        assertEquals(uuid,fixture.submit("crash-after-real-commit").get("job_id"));fixture.billingSafe();
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_uuid=?",uuid);
        reconcile(uuid);refundedOnce();
        assertEquals(0,fixture.count("load_plans"));
    }
}
