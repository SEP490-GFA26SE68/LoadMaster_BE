package fu.se184491.loadmaster_be.service.optimization;

import com.fasterxml.jackson.databind.*;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import ch.qos.logback.classic.*;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP + disposable PostgreSQL; no application configuration or real identity provider. */
class OrientationIntegrationPostgresTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path EVIDENCE=Path.of(System.getProperty("orientation.evidence","docs/reports/evidence/deterministic-rejection")).toAbsolutePath();
    static final Map<String,Level> originalLevels=new HashMap<>();
    static Process python;
    static Path temp;
    static String nonce,base;
    static OptimizationSubmissionService submission;
    static OptimizationClient actualClient;
    static ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;
    static final HttpClient HTTP=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeAll static void start() throws Exception {
        Files.createDirectories(EVIDENCE);
        for(String name:List.of("org.testcontainers","tc","org.hibernate","org.springframework")) {
            Logger logger=(Logger)org.slf4j.LoggerFactory.getLogger(name);originalLevels.put(name,logger.getLevel());logger.setLevel(Level.WARN);
        }
        OptimizationCreditPostgresTest.start();
        nonce=UUID.randomUUID().toString();
        var sql=OptimizationCreditPostgresTest.sql;
        sql.execute("CREATE TABLE orientation_test_marker(marker uuid NOT NULL)");
        sql.update("INSERT INTO orientation_test_marker VALUES(?::uuid)",nonce);
        sql.execute("CREATE UNIQUE INDEX orientation_usage_unique ON credit_transactions(reference) WHERE type='USAGE'");
        temp=Files.createTempDirectory("loadmaster-orientation-test-");
        var pg=OptimizationCreditPostgresTest.postgres;
        var cfg=new LinkedHashMap<String,Object>();
        cfg.put("host","127.0.0.1");cfg.put("pg_port",pg.getMappedPort(5432));cfg.put("database",pg.getDatabaseName());
        cfg.put("username",pg.getUsername());cfg.put("password",pg.getPassword());cfg.put("nonce",nonce);
        var root=Path.of("../OptimizeService").toAbsolutePath().normalize();
        cfg.put("python_root",root.toString());cfg.put("ready_file",temp.resolve("ready.json").toString());
        Files.writeString(temp.resolve("config.json"),JSON.writeValueAsString(cfg));
        String executable = System.getProperty("loadmaster.test.python", System.getenv("LOADMASTER_TEST_PYTHON"));
        if (executable == null || executable.isBlank()) {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            Path local = root.resolve(windows ? ".venv/Scripts/python.exe" : ".venv/bin/python");
            executable = Files.isRegularFile(local) ? local.toString() : (windows ? "python" : "python3");
        }
        python=new ProcessBuilder(executable,
            Path.of("src/test/resources/orientation-integration/isolated_python_server.py").toAbsolutePath().toString(),temp.resolve("config.json").toString())
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(EVIDENCE.resolve("python-http.log").toFile()).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(!Files.exists(temp.resolve("ready.json")) && python.isAlive() && System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(Files.exists(temp.resolve("ready.json")),"Owned Python did not start; inspect evidence/python-http.log");
        int port=JSON.readTree(Files.readString(temp.resolve("ready.json"))).path("port").asInt();
        assertTrue(port>0 && port!=8000 && port!=5432);
        base="http://127.0.0.1:"+port;
        while(true) {try {control("GET","ready");break;} catch(Exception ex){if(System.nanoTime()>deadline)throw ex;Thread.sleep(100);}}
        var token=mock(ServiceTokenProvider.class);
        when(token.getOptimizationServiceToken()).thenReturn("SYNTHETIC_SERVICE_TOKEN_SENTINEL");
        var client=new OptimizationClient(new fu.se184491.loadmaster_be.config.RestClientConfig().optimizationRestClient(base),token);
        actualClient=client;
        submission=new OptimizationSubmissionService(OptimizationCreditPostgresTest.admission,client);
        logs=new ListAppender<>();logs.start();((Logger)org.slf4j.LoggerFactory.getLogger(OptimizationClient.class)).addAppender(logs);
        Files.writeString(EVIDENCE.resolve("isolated-endpoints.json"),JSON.writeValueAsString(Map.of("pythonPort",port,"postgresPort",pg.getMappedPort(5432),"database",pg.getDatabaseName(),"identity","mock signature; real role/azp guards")));
    }
    static JsonNode control(String method,String path) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+"/__test__/"+path)).timeout(Duration.ofSeconds(20)).header("X-Test-Nonce",nonce);
        var response=HTTP.send(method.equals("GET")?builder.GET().build():builder.POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),"Owned fixture endpoint failed (see local Python log)");
        return JSON.readTree(response.body());
    }
    @BeforeEach void seed() throws Exception {
        var sql=OptimizationCreditPostgresTest.sql;
        sql.execute("TRUNCATE package_placements,unplaced_packages,load_plans,optimization_jobs,credit_transactions,credit_accounts,company_subscriptions,subscription_plans,trips,companies,delivery_requirement_packages,cargo_package_packing_profiles,packages,orders,delivery_stops,delivery_requirements,vehicles,vehicle_types,package_types,vehicle_type_obstacles RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture','T1','fixture@example.test','ACTIVE')");
        sql.update("INSERT INTO trips(id,company_id,status) VALUES(7,1,'DRAFT')");
        sql.update("INSERT INTO subscription_plans(id,plan_code,name,tier,algorithm_tier,active) VALUES(1,'BASIC_MONTHLY','Basic','BASIC','EP_DBLF',true)");
        sql.update("INSERT INTO company_subscriptions(id,company_id,plan_id,status,started_at,expires_at) VALUES(1,1,1,'ACTIVE',to_char(localtimestamp-interval '1 day','YYYY-MM-DD HH24:MI:SS'),to_char(localtimestamp+interval '1 day','YYYY-MM-DD HH24:MI:SS'))");
        sql.update("INSERT INTO credit_accounts(id,company_id,balance,version) VALUES(1,1,100,0)");
        reset(OptimizationCreditPostgresTest.user);when(OptimizationCreditPostgresTest.user.getCurrentCompanyId()).thenReturn(1L);
        logs.list.clear();
    }
    Map<String,String> submit(String key){return submission.submitInitial(7L,key).getData();}
    int count(String table){return OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    String status(){return OptimizationCreditPostgresTest.sql.queryForObject("SELECT status FROM optimization_jobs",String.class);}
    void billingSafe(){
        var sql=OptimizationCreditPostgresTest.sql;
        assertEquals(1,count("optimization_jobs"));assertEquals(1,count("credit_transactions"));
        assertEquals(98,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(-2,sql.queryForObject("SELECT amount FROM credit_transactions WHERE type='USAGE'",Integer.class));
        assertEquals(false,sql.queryForObject("SELECT refunded FROM credit_transactions",Boolean.class));
        assertEquals(sql.queryForObject("SELECT job_uuid FROM optimization_jobs",String.class),sql.queryForObject("SELECT reference FROM credit_transactions",String.class));
    }
    void evidence(String name,JsonNode state,Map<String,String> result) throws Exception {
        Files.writeString(EVIDENCE.resolve(name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("python",state,"spring",result,"balance",98,"usageCount",1,"status",status())));
        String all=JSON.writeValueAsString(state.path("events"))+logs.list.stream().map(e->e.getFormattedMessage()).reduce("",String::concat);
        for(String secret:List.of("SYNTHETIC_SERVICE_TOKEN_SENTINEL","PRIVATE_PACKAGE_CUSTOMER_SENTINEL","postgresql://","password="))assertFalse(all.contains(secret));
        for(var request:state.path("http")) {
            String id=request.path("request_id").asText();assertDoesNotThrow(()->UUID.fromString(id));
            assertTrue(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("request_id="+id)));
            assertTrue(state.path("events").toString().contains(id));
        }
        assertEquals(0,state.path("blocked_network_attempts").asInt());
        assertEquals(0,state.path("refund_calls").asInt());assertEquals(0,state.path("deduct_calls").asInt());
    }
    @Test void validFiveProfilesAckBuildSolvePersistAndRetry() throws Exception {
        control("POST","seed?mode=valid");var result=submit("valid-action");
        assertEquals("ACCEPTED",result.get("dispatch_status"));
        long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(List.of("PENDING","RUNNING").contains(status()) && System.nanoTime()<until)Thread.sleep(100);
        var state=control("GET","state");evidence("valid-five",state,result);
        assertEquals("COMPLETED",status());assertEquals(1,state.path("solve_calls").asInt());
        assertTrue(state.path("persisted_verified").asBoolean());
        assertEquals(202,state.path("http").get(0).path("status").asInt());
        assertEquals(result.get("job_id"),state.path("http").get(0).path("job_uuid").asText());
        assertEquals(result.get("job_id"),state.path("builds").get(0).path("job_uuid").asText());
        assertEquals(1,count("load_plans"));assertEquals(5,count("package_placements"));assertEquals(0,count("unplaced_packages"));
        assertEquals(5,OptimizationCreditPostgresTest.sql.queryForObject("SELECT count(DISTINCT package_id) FROM package_placements",Integer.class));
        var retry=submit("valid-action");assertEquals(result.get("job_id"),retry.get("job_id"));assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));
        assertEquals(1,control("GET","state").path("http").size());billingSafe();
    }
    @Test void invalid400PreservesTypedCodeAndRetryDoesNotChargeAgain() throws Exception {
        control("POST","seed?mode=invalid");var first=rejected("invalid-action");var retry=rejected("invalid-action");
        assertEquals("PACKING_PROFILE_ORIENTATION_INVALID",first.get("error_code"));assertEquals(first.get("error_code"),retry.get("error_code"));
        assertEquals(first.get("job_id"),retry.get("job_id"));assertEquals("PENDING",status());
        var state=control("GET","state");assertEquals(2,state.path("http").size());
        for(var http:state.path("http")){assertEquals(400,http.path("status").asInt());assertEquals("PACKING_PROFILE_ORIENTATION_INVALID",http.path("code").asText());}
        assertEquals(0,state.path("run_calls").asInt());assertEquals(0,state.path("solve_calls").asInt());
        assertEquals(0,count("load_plans"));billingSafe();evidence("invalid-retry",state,first);
    }
    @Test void concurrentSameKeyInvalidRequestsStillReserveAndBillOnlyOnce() throws Exception {
        control("POST","seed?mode=invalid");var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->rejected("concurrent"));var b=pool.submit(()->rejected("concurrent"));
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertEquals(first.get("job_id"),second.get("job_id"));billingSafe();
            var state=control("GET","state");assertEquals(0,state.path("run_calls").asInt());evidence("concurrent-invalid",state,first);
        } finally {pool.shutdownNow();}
    }
    Map<String,String> rejected(String key) {
        var error=assertThrows(fu.se184491.loadmaster_be.client.OptimizationDispatchRejectedException.class,()->submit(key));
        assertEquals(fu.se184491.loadmaster_be.exception.ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID,error.getErrorCode());
        return Map.of("error_code",error.getErrorCode().name(),"job_id",OptimizationCreditPostgresTest.sql.queryForObject("SELECT job_uuid FROM optimization_jobs",String.class));
    }
    JsonNode awaitCompleted() throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        JsonNode state;
        do {
            state=control("GET","state");
            if(status().equals("COMPLETED") && state.path("run_calls").asInt()==state.path("finished_calls").asInt())return state;
            Thread.sleep(50);
        } while(System.nanoTime()<until);
        fail("Owned fixture job did not complete");return null;
    }
    @Test void rejectionAfterOtherRequestAckMustNotFailOrRefundPendingJob() throws Exception {
        control("POST","seed?mode=valid");control("POST","gate?phase=worker&hold=true");
        try {
            var accepted=submit("ack-race");assertEquals("ACCEPTED",accepted.get("dispatch_status"));assertEquals("PENDING",status());
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='MALFORMED_SYNTHETIC' WHERE id=1");
            var error=rejected("ack-race");assertEquals(accepted.get("job_id"),error.get("job_id"));
            assertEquals("PENDING",status());billingSafe();
        } finally {
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='0,2' WHERE id=1");
            control("POST","gate?phase=worker&hold=false");
        }
        var state=awaitCompleted();assertEquals(1,state.path("solve_calls").asInt());assertTrue(state.path("persisted_verified").asBoolean());
        assertEquals(1,count("load_plans"));billingSafe();evidence("ack-before-rejection",state,Map.of("job_id",state.path("builds").get(0).path("job_uuid").asText(),"dispatch_status","ACCEPTED"));
    }
    @Test void concurrentValidDispatchSchedulesAtMostOneSolverByClaim() throws Exception {
        control("POST","seed?mode=valid");control("POST","gate?phase=worker&hold=true");
        var pool=Executors.newFixedThreadPool(2);Map<String,String> result;
        try {
            var a=pool.submit(()->submit("valid-race"));var b=pool.submit(()->submit("valid-race"));
            result=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
            assertEquals(result.get("job_id"),second.get("job_id"));billingSafe();
            assertEquals("PENDING",status());
        } finally {control("POST","gate?phase=worker&hold=false");pool.shutdownNow();}
        var state=awaitCompleted();assertEquals(2,state.path("run_calls").asInt());assertEquals(1,state.path("solve_calls").asInt());
        assertEquals(1,count("load_plans"));billingSafe();evidence("duplicate-valid-claim",state,result);
    }
    @Test void runningRetryDoesNotDispatchOrRefundEvenIfProfilesHaveChanged() throws Exception {
        control("POST","seed?mode=valid");control("POST","gate?phase=solver&hold=true");
        Map<String,String> accepted;
        try {
            accepted=submit("running-race");
            long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(!status().equals("RUNNING") && System.nanoTime()<until)Thread.sleep(50);
            assertEquals("RUNNING",status());
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='MALFORMED_SYNTHETIC' WHERE id=1");
            var retry=submit("running-race");assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));
            assertEquals(accepted.get("job_id"),retry.get("job_id"));assertEquals("RUNNING",status());billingSafe();
        } finally {
            OptimizationCreditPostgresTest.sql.update("UPDATE cargo_package_packing_profiles SET orientation='0,2' WHERE id=1");
            control("POST","gate?phase=solver&hold=false");
        }
        var state=awaitCompleted();assertEquals(1,state.path("http").size());billingSafe();evidence("running-retry",state,accepted);
    }
    @Test void lostAckAfterRealRemote202PreservesBillingAndJob() throws Exception {
        control("POST","seed?mode=valid");
        var lossy=mock(OptimizationClient.class);
        when(lossy.createJob(any())).thenAnswer(call->{actualClient.createJob(call.getArgument(0));throw new org.springframework.web.client.ResourceAccessException("Synthetic ACK loss after HTTP202");});
        var unknown=new OptimizationSubmissionService(OptimizationCreditPostgresTest.admission,lossy).submitInitial(7L,"lost-ack").getData();
        assertEquals("UNKNOWN",unknown.get("dispatch_status"));
        var state=awaitCompleted();assertEquals(202,state.path("http").get(0).path("status").asInt());
        var retry=submit("lost-ack");assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));assertEquals(unknown.get("job_id"),retry.get("job_id"));
        assertEquals(1,control("GET","state").path("http").size());billingSafe();evidence("lost-ack",state,unknown);
    }
    @RepeatedTest(5) void duplicateRefundCallbacksAreIdempotentOnIsolatedFailedJob() throws Exception {
        control("POST","seed?mode=invalid");String id=rejected("callback").get("job_id");billingSafe();
        // Explicit synthetic terminal fixture; rejection itself never triggers these callbacks.
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET status='FAILED' WHERE job_uuid=?",id);
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        Callable<Void> callback=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));OptimizationCreditPostgresTest.credits.refundCredit(1L,id);return null;};
        try {var a=pool.submit(callback);var b=pool.submit(callback);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}
        finally {go.countDown();pool.shutdownNow();}
        OptimizationCreditPostgresTest.credits.refundCredit(1L,id);
        var sql=OptimizationCreditPostgresTest.sql;
        assertEquals(100,sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='USAGE'",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='REFUND'",Integer.class));
        assertEquals(true,sql.queryForObject("SELECT refunded FROM credit_transactions WHERE type='USAGE'",Boolean.class));
    }
    @Test void refundRefreshDoesNotDiscardPendingChangesInOuterTransaction() throws Exception {
        control("POST","seed?mode=invalid");String id=rejected("outer-tx").get("job_id");
        OptimizationCreditPostgresTest.sql.update("UPDATE optimization_jobs SET status='FAILED' WHERE job_uuid=?",id);
        var accounts=OptimizationCreditPostgresTest.context.getBean(fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository.class);
        OptimizationCreditPostgresTest.transaction.executeWithoutResult(tx->{
            var account=accounts.findByCompanyId(1L).orElseThrow();
            account.setBalance(97); // Synthetic pending adjustment; refresh must not silently discard it.
            OptimizationCreditPostgresTest.credits.refundCredit(1L,id);
        });
        assertEquals(99,OptimizationCreditPostgresTest.sql.queryForObject("SELECT balance FROM credit_accounts",Integer.class));
        assertEquals(2,count("credit_transactions"));
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="orientation.workflow.red",matches="true")
    void redTerminalDispositionNeedsDispatchFencing() throws Exception {
        control("POST","seed?mode=invalid");rejected("terminal-policy-review");billingSafe();
        assertNotEquals("PENDING",status(),"Terminal policy is BLOCKED until concurrent ACK-before-claim is fenced");
    }
    /** Opt-in red regression: deterministic pre-ACK rejection is currently reported as ambiguous/PENDING. */
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="orientation.workflow.red",matches="true")
    void redDeterministic400MustNotRemainIndistinguishableFromLostAck() throws Exception {
        control("POST","seed?mode=invalid");var result=submit("red-policy-review");
        billingSafe();
        assertAll(()->assertNotEquals("UNKNOWN",result.get("dispatch_status"),"Deterministic Python 400 is not an unknown ACK"),
            ()->assertNotEquals("PENDING",status(),"No worker was scheduled; pending recovery obscures the actual validation cause"));
    }
    @AfterAll static void stop() throws Exception {
        if(python!=null){python.destroy();if(!python.waitFor(5,TimeUnit.SECONDS))python.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
        if(temp!=null)Files.deleteIfExists(temp.resolve("config.json"));
        OptimizationCreditPostgresTest.stop();
        if(logs!=null){((Logger)org.slf4j.LoggerFactory.getLogger(OptimizationClient.class)).detachAppender(logs);logs.stop();}
        originalLevels.forEach((name,level)->((Logger)org.slf4j.LoggerFactory.getLogger(name)).setLevel(level));
    }
}
