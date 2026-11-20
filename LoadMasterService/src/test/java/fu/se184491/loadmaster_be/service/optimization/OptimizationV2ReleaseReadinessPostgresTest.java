package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.support.TestSqlFixtures;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Audit evidence only: shared app profiles, credentials, and databases are never used. */
class OptimizationV2ReleaseReadinessPostgresTest {
    final OrientationIntegrationPostgresTest fixture=new OrientationIntegrationPostgresTest();
    static String proposal;
    @BeforeAll static void start() throws Exception {
        System.setProperty("orientation.evidence","docs/reports/evidence/v2-readiness/schema-http");
        OrientationIntegrationPostgresTest.start(); // flags stay FALSE, no real worker/config change
        proposal=TestSqlFixtures.read("proposed-optimization-lifecycle-v2.sql");
        var sql=OptimizationCreditPostgresTest.sql;
        sql.execute("ALTER TABLE optimization_jobs ADD CONSTRAINT audit_job_trip_fk FOREIGN KEY(trip_id) REFERENCES trips(id)");
        sql.execute("ALTER TABLE load_plans ADD CONSTRAINT audit_plan_job_fk FOREIGN KEY(job_id) REFERENCES optimization_jobs(id)");
        sql.execute("ALTER TABLE credit_transactions ADD CONSTRAINT audit_tx_account_fk FOREIGN KEY(credit_account_id) REFERENCES credit_accounts(id)");
        sql.execute("CREATE UNIQUE INDEX audit_tx_reference_type ON credit_transactions(reference,type)");
        sql.execute("ALTER TABLE optimization_jobs ADD CONSTRAINT audit_job_status CHECK(status IN ('PENDING','RUNNING','COMPLETED','SUCCESS','FAILED','TIMEOUT','NO_SOLUTION','PARTIAL'))");
    }
    @AfterAll static void stop() throws Exception {OrientationIntegrationPostgresTest.stop();System.clearProperty("orientation.evidence");}
    String uuid;
    @BeforeEach void seed() throws Exception {
        fixture.seed();
        uuid=OptimizationCreditPostgresTest.admission.reserve(7L,null,
            fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective.MAX_VOLUME,60,null,"readiness-legacy").getJobUuid();
        assertNull(OptimizationCreditPostgresTest.context.getBean(OptimizationJobRepository.class).findByJobUuid(uuid).orElseThrow().getExecutionGeneration());
    }
    void dropMetadata() {OptimizationCreditPostgresTest.sql.execute("ALTER TABLE optimization_jobs DROP COLUMN execution_generation,DROP COLUMN dispatch_expires_at,DROP COLUMN lease_expires_at,DROP COLUMN execution_started_at");}
    void restore() {OptimizationCreditPostgresTest.sql.execute(proposal);}
    static String state(Throwable error) {
        for(Throwable c=error;c!=null;c=c.getCause()) if(c instanceof SQLException sql)return sql.getSQLState();
        return null;
    }
    String fingerprint(String table,boolean legacyJob) {
        String row=legacyJob?"to_jsonb(t)-'execution_generation'-'dispatch_expires_at'-'lease_expires_at'-'execution_started_at'":"to_jsonb(t)";
        return OptimizationCreditPostgresTest.sql.queryForObject("SELECT md5(coalesce(jsonb_agg("+row+" ORDER BY id)::text,'[]')) FROM "+table+" t",String.class);
    }
    @Test void flagsOffStillRequireMetadataForBothActualOrms() throws Exception {
        dropMetadata();
        try {
            var error=assertThrows(RuntimeException.class,()->OptimizationCreditPostgresTest.context.getBean(OptimizationJobRepository.class).findByJobUuid(uuid));
            assertEquals("42703",state(error));
            var python=OrientationIntegrationPostgresTest.control("GET","mapping-probe?uuid="+uuid);
            assertFalse(python.path("v2_flag").asBoolean());assertFalse(python.path("mapped").asBoolean());
            assertEquals("42703",python.path("sqlstate").asText());
        } finally {restore();}
        assertTrue(OrientationIntegrationPostgresTest.control("GET","mapping-probe?uuid="+uuid).path("mapped").asBoolean());
    }
    @Test void additiveUpgradePreservesAllEightStatusesBillingDependenciesAndCatalogs() {
        var sql=OptimizationCreditPostgresTest.sql;
        for(String status:List.of("RUNNING","COMPLETED","SUCCESS","FAILED","TIMEOUT","NO_SOLUTION","PARTIAL"))
            sql.update("INSERT INTO optimization_jobs(trip_id,job_uuid,status,algorithm_name,company_id) VALUES(7,?,?,'EP_DBLF','1')",UUID.randomUUID().toString(),status);
        sql.update("INSERT INTO load_plans(job_id,plan_name,packed_items_count,is_approved) SELECT id,'Legacy approved',0,true FROM optimization_jobs WHERE status='COMPLETED'");
        var tables=List.of("optimization_jobs","load_plans","credit_accounts","credit_transactions","companies","company_subscriptions","subscription_plans","trips");
        var before=new LinkedHashMap<String,String>();for(String table:tables)before.put(table,fingerprint(table,table.equals("optimization_jobs")));
        String constraints=sql.queryForObject("SELECT md5(string_agg(oid::text||conname||pg_get_constraintdef(oid),',' ORDER BY oid)) FROM pg_constraint WHERE connamespace='public'::regnamespace",String.class);
        String indexes=sql.queryForObject("SELECT md5(string_agg(indexdef,',' ORDER BY indexname)) FROM pg_indexes WHERE schemaname='public'",String.class);
        long sequence=sql.queryForObject("SELECT last_value FROM optimization_jobs_id_seq",Long.class);
        dropMetadata();restore();restore();
        for(String table:tables)assertEquals(before.get(table),fingerprint(table,table.equals("optimization_jobs")),table);
        assertEquals(8,sql.queryForObject("SELECT count(*) FROM optimization_jobs WHERE execution_generation IS NULL AND dispatch_expires_at IS NULL AND lease_expires_at IS NULL AND execution_started_at IS NULL",Integer.class));
        assertEquals(constraints,sql.queryForObject("SELECT md5(string_agg(oid::text||conname||pg_get_constraintdef(oid),',' ORDER BY oid)) FROM pg_constraint WHERE connamespace='public'::regnamespace",String.class));
        assertEquals(indexes,sql.queryForObject("SELECT md5(string_agg(indexdef,',' ORDER BY indexname)) FROM pg_indexes WHERE schemaname='public'",String.class));
        assertEquals(sequence,sql.queryForObject("SELECT last_value FROM optimization_jobs_id_seq",Long.class));
    }
    @Test void wrongExistingTypeRollsBackWholeProposal() {
        var sql=OptimizationCreditPostgresTest.sql;dropMetadata();
        sql.execute("ALTER TABLE optimization_jobs ADD COLUMN execution_generation varchar(20)");
        try {
            assertEquals("P0001",state(assertThrows(RuntimeException.class,()->sql.execute(proposal))));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='optimization_jobs' AND column_name IN ('dispatch_expires_at','lease_expires_at','execution_started_at')",Integer.class));
            assertEquals(98,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        } finally {sql.execute("ALTER TABLE optimization_jobs DROP COLUMN execution_generation");restore();}
    }
    @Test void missingAlterPrivilegesCannotPartiallyMigrate() throws Exception {
        dropMetadata();
        try(Connection c=OptimizationCreditPostgresTest.context.getBean(DataSource.class).getConnection()) {
            try(Statement s=c.createStatement()) {s.execute("CREATE ROLE readiness_reader NOLOGIN");s.execute("SET ROLE readiness_reader");
                SQLException error=assertThrows(SQLException.class,()->s.execute(proposal));assertEquals("42501",error.getSQLState());}
        } finally {restore();}
        assertEquals(98,OptimizationCreditPostgresTest.sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
    }
    @Test void lockTimeoutDoesNotLeavePartialSchemaChanges() throws Exception {
        dropMetadata();
        try(Connection c=OptimizationCreditPostgresTest.context.getBean(DataSource.class).getConnection()) {
            c.setAutoCommit(false);
            try(Statement s=c.createStatement()) {
                s.executeQuery("SELECT id FROM optimization_jobs").close(); // ACCESS SHARE held until rollback
                assertEquals("55P03",state(assertThrows(RuntimeException.class,()->OptimizationCreditPostgresTest.sql.execute(proposal))));
            } finally {c.rollback();}
        } finally {restore();}
        assertEquals(98,OptimizationCreditPostgresTest.sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
    }
    @Test void metadataInstantRoundTripMatchesPythonUtc() throws Exception {
        Instant instant=Instant.parse("2026-11-15T10:00:00.123456Z");
        var jobs=OptimizationCreditPostgresTest.context.getBean(OptimizationJobRepository.class);
        OptimizationCreditPostgresTest.transaction.executeWithoutResult(tx->{var job=jobs.findByJobUuid(uuid).orElseThrow();job.setDispatchExpiresAt(instant);jobs.saveAndFlush(job);});
        assertEquals(instant,jobs.findByJobUuid(uuid).orElseThrow().getDispatchExpiresAt());
        assertEquals("2026-11-15T10:00:00.123456+00:00",OrientationIntegrationPostgresTest.control("GET","mapping-probe?uuid="+uuid).path("dispatch_expires_at").asText());
        assertEquals(3,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='optimization_jobs' AND column_name IN ('dispatch_expires_at','lease_expires_at','execution_started_at') AND data_type='timestamp with time zone'",Integer.class));
    }
    @Test void flagsOffV2HttpRefusesWithoutExecutingOrChargingAgain() throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(OrientationIntegrationPostgresTest.base+"/api/v2/optimization/jobs"))
            .header("Authorization","Bearer SYNTHETIC_SERVICE_TOKEN_SENTINEL").header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"trip_id\":7,\"job_uuid\":\""+uuid+"\"}")).build();
        assertEquals(503,OrientationIntegrationPostgresTest.HTTP.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());fixture.billingSafe();
        assertEquals(0,OrientationIntegrationPostgresTest.control("GET","state").path("run_calls").asInt());
    }
    @Test void upgradedV1RefusesV2ButOldSqlHasNoDatabaseFence() throws Exception {
        var sql=OptimizationCreditPostgresTest.sql;
        sql.update("UPDATE optimization_jobs SET execution_generation=1,status='RUNNING',lease_expires_at=clock_timestamp()+interval '10 minutes' WHERE job_uuid=?",uuid);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(OrientationIntegrationPostgresTest.base+"/api/v1/optimization/jobs"))
            .header("Authorization","Bearer SYNTHETIC_SERVICE_TOKEN_SENTINEL").header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"trip_id\":7,\"job_uuid\":\""+uuid+"\"}")).build();
        assertEquals(409,OrientationIntegrationPostgresTest.HTTP.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        // Intentional negative evidence: a pre-V2 binary can still issue its old unconditional SQL.
        assertEquals(1,sql.update("UPDATE optimization_jobs SET status='FAILED',failure_code='JOB_INTERRUPTED' WHERE job_uuid=? AND status IN ('PENDING','RUNNING')",uuid));
        assertEquals(98,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
    }
}
