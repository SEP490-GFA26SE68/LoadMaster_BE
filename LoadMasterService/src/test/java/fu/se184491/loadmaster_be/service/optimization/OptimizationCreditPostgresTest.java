package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.support.TestSqlFixtures;

import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.*;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptimizationCreditPostgresTest {
    static PostgreSQLContainer postgres;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static OptimizationAdmissionService admission;
    static CreditService credits;
    static CurrentUserService user;
    static TransactionTemplate transaction;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
        includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE, classes={
            CompanyRepository.class,TripRepository.class,LoadPlanRepository.class,OptimizationJobRepository.class,
            CompanySubscriptionRepository.class,CreditAccountRepository.class,CreditTransactionRepository.class}),
        excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*(?:CompanyRepository|TripRepository|LoadPlanRepository|OptimizationJobRepository|CompanySubscriptionRepository|CreditAccountRepository|CreditTransactionRepository)$).*$"))
    @Import({CreditServiceImpl.class,OptimizationEntitlementService.class,OptimizationAdmissionService.class,OptimizationLifecycleReconciler.class})
    static class Config {
        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr(){return mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);}
        @Bean DataSource dataSource() {
            if (postgres.getMappedPort(5432)==5432) throw new IllegalStateException("Shared database port is forbidden");
            return new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            var bean=new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setPackagesToScan("fu.se184491.loadmaster_be.entity");
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none", "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
            return bean;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {return new JpaTransactionManager(factory);}
        @Bean fu.se184491.loadmaster_be.repository.common.AuditLogRepository audit(){ return mock(fu.se184491.loadmaster_be.repository.common.AuditLogRepository.class); }
        @Bean fu.se184491.loadmaster_be.service.payment.PaymentGatewayService payment(){return mock(fu.se184491.loadmaster_be.service.payment.PaymentGatewayService.class); }
        @Bean CurrentUserService currentUserService() {return mock(CurrentUserService.class);}
    }

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17");
        postgres.start();
        context=new AnnotationConfigApplicationContext(Config.class);
        sql=new JdbcTemplate(context.getBean(DataSource.class));
        // Minimal isolated schema. No production properties, migrations or startup initializers are loaded.
        for(String ddl: SCHEMA.split(";")) if(!ddl.isBlank()) sql.execute(ddl);
        try {
            String proposal=TestSqlFixtures.read("proposed-optimization-lifecycle-v2.sql");
            sql.execute(proposal);
            sql.execute(proposal); // rerun safety on an isolated schema
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        admission=context.getBean(OptimizationAdmissionService.class);
        credits=context.getBean(CreditService.class);
        user=context.getBean(CurrentUserService.class);
        transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void stop() {if(context!=null)context.close();if(postgres!=null)postgres.stop();}
    @BeforeEach void seed() {
        // Only the newly created disposable container is ever modified by these fixtures.
        sql.execute("TRUNCATE credit_transactions,optimization_jobs,load_plans,credit_accounts,company_subscriptions,subscription_plans,trips,companies RESTART IDENTITY");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','C1','T1','test@example.test','ACTIVE'),(2,'C2','C2','T2','test2@example.test','ACTIVE')");
        sql.update("INSERT INTO trips(id,company_id,status) VALUES(7,1,'DRAFT'),(8,2,'DRAFT'),(9,NULL,'DRAFT')");
        sql.update("INSERT INTO subscription_plans(id,plan_code,name,tier,algorithm_tier,active) VALUES(1,'BASIC_MONTHLY','Basic','BASIC','EP_DBLF',true)");
        sql.update("INSERT INTO company_subscriptions(id,company_id,plan_id,status,started_at,expires_at) VALUES(1,1,1,'ACTIVE',to_char(localtimestamp-interval '1 day','YYYY-MM-DD HH24:MI:SS'),to_char(localtimestamp+interval '1 day','YYYY-MM-DD HH24:MI:SS'))");
        sql.update("INSERT INTO credit_accounts(id,company_id,balance,version) VALUES(1,1,10,0)");
        reset(user);when(user.getCurrentCompanyId()).thenReturn(1L);
    }
    OptimizationJob reserve(String key){return admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,60,null,key);}
    int balance(){return sql.queryForObject("SELECT balance FROM credit_accounts WHERE company_id=1",Integer.class);}
    int usages(){return sql.queryForObject("SELECT count(*) FROM credit_transactions WHERE type='USAGE'",Integer.class);}
    int jobs(){return sql.queryForObject("SELECT count(*) FROM optimization_jobs",Integer.class);}

    @Test void acceptedByPythonButTransportTimeoutDoesNotChargeOrDispatchAgainWhenRunning() {
        var client=mock(fu.se184491.loadmaster_be.client.OptimizationClient.class);
        var submission=new OptimizationSubmissionService(admission,client);
        when(client.createJob(any())).thenAnswer(call -> {
            var request=(Map<?,?>)call.getArgument(0);
            // Simulate Python claiming the already committed row before its ACK is lost.
            assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
            sql.update("UPDATE optimization_jobs SET status='RUNNING' WHERE job_uuid=?",request.get("job_uuid"));
            throw new org.springframework.web.client.ResourceAccessException("Read timed out");
        });
        var first=submission.submitInitial(7L,"timeout-key").getData();
        assertEquals("UNKNOWN",first.get("dispatch_status"));
        var retry=submission.submitInitial(7L,"timeout-key").getData();
        assertEquals(first.get("job_id"),retry.get("job_id"));
        assertEquals("ALREADY_ACCEPTED",retry.get("dispatch_status"));
        verify(client,times(1)).createJob(any());
        assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
    }

    @Test void initialCostsTwoAndPersistsServerCompanyAndTier() {
        var j=reserve("initial");assertEquals(8,balance());assertEquals(1,usages());assertEquals("1",j.getCompanyId());
        assertEquals(OptimizationAlgorithm.EP_DBLF,j.getAlgorithmName());
        assertEquals(j.getJobUuid(),sql.queryForObject("select reference from credit_transactions",String.class));
    }
    @Test void parentPlanActionCostsOne() {
        var first=reserve("initial");
        sql.update("INSERT INTO load_plans(id,job_id) VALUES(47,?)",first.getId());
        admission.reserve(7L,47L,OptimizationObjective.MAX_VOLUME,60,null,"rerun");
        assertEquals(7,balance());assertEquals(2,usages());
    }
    @Test void insufficientCreditRollsBackJobAndLedger() {
        sql.update("UPDATE credit_accounts SET balance=1");
        assertThrows(InsufficientCreditsException.class,()->reserve("poor"));
        assertEquals(1,balance());assertEquals(0,jobs());assertEquals(0,usages());
    }
    @Test void expiredEvenWithActiveStatusRejectsWithoutJobOrDeduction() {
        sql.update("UPDATE company_subscriptions SET expires_at=to_char(localtimestamp-interval '1 hour','YYYY-MM-DD HH24:MI:SS')");
        assertThrows(AppException.class,()->reserve("expired"));assertEquals(10,balance());assertEquals(0,jobs());
    }
    @Test void missingSubscriptionRejects() {
        sql.update("DELETE FROM company_subscriptions");
        assertThrows(AppException.class,()->reserve("missing"));assertEquals(0,jobs());
    }
    @Test void futureSubscriptionRejects() {
        sql.update("UPDATE company_subscriptions SET started_at=to_char(localtimestamp+interval '1 hour','YYYY-MM-DD HH24:MI:SS')");
        assertThrows(AppException.class,()->reserve("future"));assertEquals(10,balance());
    }
    @Test void otherCompanyOrMissingTripCompanyRejects() {
        for(long id:new long[]{8,9}) assertThrows(org.springframework.security.access.AccessDeniedException.class,
            ()->admission.reserve(id,null,OptimizationObjective.MAX_VOLUME,60,null,"foreign"+id));
        assertEquals(0,jobs());assertEquals(10,balance());
    }
    @Test void userWithoutCompanyRejects() {
        when(user.getCurrentCompanyId()).thenReturn(null);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("no-company"));assertEquals(0,jobs());
    }
    @Test void sameKeyRetryAfterCommittedResponseLossReturnsSameJob() {
        var a=reserve("retry");var b=reserve("retry");
        assertEquals(a.getJobUuid(),b.getJobUuid());assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
    }
    @Test void newKeysAreSeparateBilledActions() {
        assertNotEquals(reserve("one").getJobUuid(),reserve("two").getJobUuid());
        assertEquals(2,jobs());assertEquals(2,usages());assertEquals(6,balance());
    }
    @Test void conflictingPayloadWithSameKeyRejects() {
        reserve("same");
        assertThrows(AppException.class,()->admission.reserve(7L,null,OptimizationObjective.MAX_VOLUME,120,null,"same"));
        assertEquals(1,jobs());assertEquals(8,balance());
    }
    @Test void jobInsertFailureLeavesNoCharge() {
        sql.execute("ALTER TABLE optimization_jobs ADD CONSTRAINT reject_test_job CHECK (time_limit_sec<>60) NOT VALID");
        try {assertThrows(RuntimeException.class,()->reserve("insert-fails"));}
        finally {sql.execute("ALTER TABLE optimization_jobs DROP CONSTRAINT reject_test_job");}
        assertEquals(10,balance());assertEquals(0,usages());assertEquals(0,jobs());
    }
    @Test void ledgerFailureRollsBackDeductionAndJob() {
        sql.execute("ALTER TABLE credit_transactions ADD CONSTRAINT reject_test_usage CHECK (type<>'USAGE') NOT VALID");
        try {assertThrows(RuntimeException.class,()->reserve("ledger-fails"));}
        finally {sql.execute("ALTER TABLE credit_transactions DROP CONSTRAINT reject_test_usage");}
        assertEquals(10,balance());assertEquals(0,jobs());assertEquals(0,usages());
    }
    @Test void concurrentSameKeyCreatesExactlyOneJobAndCharge() throws Exception {
        concurrently(()->reserve("race"),()->reserve("race"));
        assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
    }
    @Test void concurrentFirstDeductionRechecksUsageAfterLock() throws Exception {
        sql.update("INSERT INTO optimization_jobs(trip_id,job_uuid,company_id,subscription_tier,algorithm_tier,algorithm_name,status) VALUES(7,'first-deduct-race','1','BASIC','BASIC','EP_DBLF','PENDING')");
        concurrently(()->{credits.deductCredit(1L,"first-deduct-race");return null;},
                     ()->{credits.deductCredit(1L,"first-deduct-race");return null;});
        assertEquals(1,usages());assertEquals(8,balance());assertEquals(1,jobs());
    }
    @Test void concurrentSameReferenceAfterCommitNeverChargesTwice() throws Exception {
        var j=reserve("deduct-race");
        concurrently(()->{credits.deductCredit(1L,j.getJobUuid());return null;},
                     ()->{credits.deductCredit(1L,j.getJobUuid());return null;});
        assertEquals(1,usages());assertEquals(8,balance());
    }
    @Test void concurrentDistinctJobsCannotOverdraw() throws Exception {
        sql.update("UPDATE credit_accounts SET balance=2");
        var outcomes=new java.util.concurrent.atomic.AtomicInteger();
        Callable<Object> a=()->{try{reserve("limited-one");outcomes.incrementAndGet();}catch(InsufficientCreditsException expected){}return null;};
        Callable<Object> b=()->{try{reserve("limited-two");outcomes.incrementAndGet();}catch(InsufficientCreditsException expected){}return null;};
        concurrently(a,b);assertEquals(1,outcomes.get());assertEquals(0,balance());assertEquals(1,jobs());assertEquals(1,usages());
    }
    @Test void firstTimeAccountCreationIsSerializedAndRolledBackIfUnfunded() throws Exception {
        sql.update("DELETE FROM credit_accounts");
        Callable<Object> a=()->{assertThrows(InsufficientCreditsException.class,()->reserve("new-one"));return null;};
        Callable<Object> b=()->{assertThrows(InsufficientCreditsException.class,()->reserve("new-two"));return null;};
        concurrently(a,b);assertEquals(0,jobs());assertEquals(0,usages());
    }
    @Test void proAndUltimateSnapshotUseConfiguredAlgorithms() {
        for(String tier:new String[]{"PRO","ULTIMATE"}) {
            String algo=tier.equals("PRO")?"EP_DBLF_GA":"EP_DBLF_GA_AI";
            sql.update("UPDATE subscription_plans SET tier=?,algorithm_tier=?",tier,algo);
            assertEquals(algo,reserve(tier).getAlgorithmName().name());
        }
    }
    @Test void invalidPlanAlgorithmFailsClosed() {
        sql.update("UPDATE subscription_plans SET algorithm_tier='EP_DBLF_GA'");
        assertThrows(AppException.class,()->reserve("bad-tier"));assertEquals(10,balance());assertEquals(0,jobs());
    }
    @Test void wrongCompanyCannotReuseCreditReference() {
        var j=reserve("owner");assertThrows(org.springframework.security.access.AccessDeniedException.class,
            ()->credits.deductCredit(2L,j.getJobUuid()));assertEquals(8,balance());assertEquals(1,usages());
    }
    @Test void callerCannotChooseOneCreditForInitial() {
        var j=reserve("cost");assertThrows(IllegalArgumentException.class,()->credits.deductCredit(1L,j.getJobUuid(),1));
        assertEquals(8,balance());assertEquals(1,usages());
    }
    @Test void proposedMigrationPreservesLegacyRowsAndAllowsProAlgorithm() throws Exception {
        sql.update("INSERT INTO optimization_jobs(trip_id,job_uuid,algorithm_name,status) VALUES(7,'legacy-job','EP_DBLF','COMPLETED')");
        sql.execute("ALTER TABLE optimization_jobs DROP CONSTRAINT IF EXISTS optimization_jobs_algorithm_name_check");
        sql.execute("ALTER TABLE optimization_jobs ADD CONSTRAINT optimization_jobs_algorithm_name_check CHECK (algorithm_name IN ('EP_DBLF','GENETIC_ALGORITHM'))");
        String script=TestSqlFixtures.read("proposed-phase2-credit-migration.sql");
        sql.execute(script); // ONLY this test's brand-new disposable PostgreSQL container.
        assertEquals(10,balance());assertEquals(0,usages());assertEquals(1,jobs());
        assertEquals("COMPLETED",sql.queryForObject("select status from optimization_jobs where job_uuid='legacy-job'",String.class));
        sql.update("UPDATE subscription_plans SET tier='PRO',algorithm_tier='EP_DBLF_GA'");
        assertEquals(OptimizationAlgorithm.EP_DBLF_GA,reserve("migrated-pro").getAlgorithmName());
        assertEquals(8,balance());assertEquals(1,usages());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SUSPENDED","EXPIRED"})
    void inactiveCompanyWithActiveSubscriptionCannotReserveOrCharge(String state) {
        sql.update("UPDATE companies SET status=? WHERE id=1",state);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("inactive"));
        assertEquals(10,balance());assertEquals(0,usages());assertEquals(0,jobs());
    }
    @Test void nullCompanyStatusFailsClosed() {
        sql.update("UPDATE companies SET status=NULL WHERE id=1");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("null-status"));
        assertEquals(10,balance());assertEquals(0,usages());assertEquals(0,jobs());
    }
    @Test void retryAfterSuspensionCannotBypassAdmission() {
        var first=reserve("existing");sql.update("UPDATE companies SET status='SUSPENDED' WHERE id=1");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("existing"));
        assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
        assertEquals(first.getJobUuid(),sql.queryForObject("select job_uuid from optimization_jobs",String.class));
    }
    @Test void sameKeyRetryAfterTripOwnershipChangeIsDenied() {
        reserve("moved");sql.update("UPDATE trips SET company_id=2 WHERE id=7");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("moved"));
        assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
    }
    @Test void companyStatusIsRefreshedEvenIfAlreadyManagedBeforeLock() {
        var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transaction.execute(tx->{
            var cached=em.find(fu.se184491.loadmaster_be.entity.company.Company.class,1L);
            assertEquals(fu.se184491.loadmaster_be.constant.company.CompanyStatus.ACTIVE,cached.getStatus());
            CompletableFuture.runAsync(()->sql.update("UPDATE companies SET status='SUSPENDED' WHERE id=1")).join();
            return reserve("stale-cache");
        }));
        assertEquals(0,jobs());assertEquals(0,usages());assertEquals(10,balance());
    }
    @Test void suspensionHoldingRowLockWinsBeforeAdmission() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var suspend=pool.submit(()->transaction.execute(tx->{
                sql.update("UPDATE companies SET status='SUSPENDED' WHERE id=1");locked.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));var request=pool.submit(()->reserve("suspend-race"));
            Thread.sleep(100);assertFalse(request.isDone());release.countDown();suspend.get(10,TimeUnit.SECONDS);
            var error=assertThrows(ExecutionException.class,()->request.get(10,TimeUnit.SECONDS));
            assertInstanceOf(org.springframework.security.access.AccessDeniedException.class,error.getCause());
            assertEquals(0,jobs());assertEquals(0,usages());assertEquals(10,balance());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void admissionHoldingRowLockCommitsBeforeLaterSuspension() throws Exception {
        var reserved=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var request=pool.submit(()->transaction.execute(tx->{
                var job=reserve("admit-first");reserved.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return job;
            }));
            assertTrue(reserved.await(10,TimeUnit.SECONDS));var suspend=pool.submit(()->sql.update("UPDATE companies SET status='SUSPENDED' WHERE id=1"));
            Thread.sleep(100);assertFalse(suspend.isDone());release.countDown();request.get(10,TimeUnit.SECONDS);suspend.get(10,TimeUnit.SECONDS);
            assertEquals(1,jobs());assertEquals(1,usages());assertEquals(8,balance());
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->reserve("admit-first"));
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void concurrentEnsureAccountCreatesOneZeroBalanceAccountWithoutLedgerMutation() throws Exception {
        // Explicit-ID fixture inserts must advance the disposable database's identity sequence.
        sql.queryForObject("SELECT setval(pg_get_serial_sequence('credit_accounts','id'),(SELECT max(id) FROM credit_accounts),true)",Long.class);
        concurrently(()->{credits.ensureAccount(2L);return null;},()->{credits.ensureAccount(2L);return null;});
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM credit_accounts WHERE company_id=2",Integer.class));
        assertEquals(0,sql.queryForObject("SELECT balance FROM credit_accounts WHERE company_id=2",Integer.class));
        assertEquals(0L,sql.queryForObject("SELECT version FROM credit_accounts WHERE company_id=2",Long.class));
        assertEquals(10,balance());
        assertEquals(0,usages());
        assertEquals(0,jobs());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM credit_transactions",Integer.class));
    }

    static void concurrently(Callable<?> first,Callable<?> second) throws Exception {
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return first.call();});
            Future<?> b=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return second.call();});
            a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    static final String SCHEMA="""
        CREATE TABLE companies(id BIGSERIAL PRIMARY KEY,company_code varchar(50),company_name varchar(255),tax_code varchar(50),billing_email varchar(150),status varchar(30),created_at timestamptz);
        CREATE TABLE trips(id BIGSERIAL PRIMARY KEY,company_id bigint,vehicle_id bigint,driver_user_id bigint,created_by_dispatcher_id bigint,origin_warehouse_id bigint,trip_name varchar(150),origin_name varchar(150),origin_address varchar(255),origin_latitude numeric,origin_longitude numeric,trip_code varchar(50),departure_time timestamp,status varchar(20),route_plan json,handling_class_lock varchar(20),override_reason text,created_at timestamptz,departed_at timestamp,completed_at timestamp,cancel_reason text,cancelled_from_status varchar(20),cancel_returned_count integer);
        CREATE TABLE subscription_plans(id BIGSERIAL PRIMARY KEY,plan_code varchar(50),name varchar(150),price numeric,billing_cycle varchar(30),tier varchar(20),monthly_credits int,algorithm_tier varchar(20),features text,active boolean);
        CREATE TABLE company_subscriptions(id BIGSERIAL PRIMARY KEY,company_id bigint UNIQUE,plan_id bigint,status varchar(20),started_at varchar(19),expires_at varchar(19),auto_renew boolean);
        CREATE TABLE credit_accounts(id BIGSERIAL PRIMARY KEY,company_id bigint UNIQUE,balance int NOT NULL CHECK(balance>=0),version bigint);
        CREATE TABLE credit_transactions(id BIGSERIAL PRIMARY KEY,credit_account_id bigint,amount int,type varchar(20),reference varchar(100),refunded boolean,created_at varchar(19));
        CREATE TABLE optimization_jobs(id BIGSERIAL PRIMARY KEY,trip_id bigint,job_uuid varchar(64) UNIQUE,algorithm_name varchar(50),objective varchar(50),time_limit_sec int,status varchar(50),computation_ms bigint,parent_plan_id bigint,seed int,company_id varchar(64),subscription_tier varchar(50),algorithm_tier varchar(50),created_at timestamp,failure_code varchar(100));
        CREATE TABLE load_plans(id BIGSERIAL PRIMARY KEY,job_id bigint,plan_name varchar(100),packed_items_count int,volume_utilization numeric,weight_utilization numeric,is_approved boolean,approved_by bigint,version int,parent_plan_id bigint,cog_x numeric,cog_y numeric,cog_z numeric,front_axle_load numeric,rear_axle_load numeric,rehandling_count int,objective varchar(50),candidate_label varchar(20),algorithm_name varchar(50),manually_edited boolean,is_stale boolean);
        """;
}
