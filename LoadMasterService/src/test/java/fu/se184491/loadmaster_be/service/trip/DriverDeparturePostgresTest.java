package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.*;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverServiceImpl;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.pickup.PickupDriverService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/JPA transactions; no Boot profiles, security bypass, worker or remote configuration. */
class DriverDeparturePostgresTest {
    static PostgreSQLContainer postgres;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static DriverService service;
    static CurrentUserService principal;
    static TransactionTemplate tx;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
        includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={
            TripRepository.class,LoadPlanRepository.class,LoadingExecutionRepository.class,UserRepository.class,
            AuditLogRepository.class,DeliveryStopRepository.class,DeliveryRequirementRepository.class}),
        excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
            pattern="^(?!.*(?:TripRepository|LoadPlanRepository|LoadingExecutionRepository|UserRepository|AuditLogRepository|DeliveryStopRepository|DeliveryRequirementRepository)$).*$"))
    @Import({TripDepartureReadiness.class,TripStatusService.class,DriverServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = postgres.getJdbcUrl();
            var target = java.net.URI.create(url.substring("jdbc:".length()));
            if (!"/driver_departure_test".equals(target.getPath())
                    || target.getPort() != postgres.getMappedPort(5432) || target.getPort() == 5432)
                throw new IllegalStateException("Only a fresh randomly mapped test container is allowed");
            return new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("fu.se184491.loadmaster_be.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // Creation is restricted to the fresh container above, never the application database.
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create",
                "jakarta.persistence.validation.mode","none","hibernate.show_sql","false",
                "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beanFactory)));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) { return new JpaTransactionManager(f); }
        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qrTokens() {
            // No cargo/QR data is exercised; the existing converter only needs its DI dependency.
            return mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);
        }
        @Bean CurrentUserService principal() { return mock(CurrentUserService.class); }
        @Bean PackagePlacementRepository placements() { return mock(PackagePlacementRepository.class); }
        @Bean CargoPackageRepository packages() { return mock(CargoPackageRepository.class); }
        @Bean UnloadConfirmationRepository unloaded() { return mock(UnloadConfirmationRepository.class); }
        @Bean DeliveryIssueRepository issues() { return mock(DeliveryIssueRepository.class); }
        @Bean ManualUnloadConfirmationRepository manual() { return mock(ManualUnloadConfirmationRepository.class); }
        @Bean PickupDriverService pickups() { return mock(PickupDriverService.class); }
        @Bean TripPackageResolver packageResolver() { return mock(TripPackageResolver.class); }
        @Bean TripLookupHelper tripLookup() { return mock(TripLookupHelper.class); }
        @Bean RoutePlanRepository routes() { return mock(RoutePlanRepository.class); }
    }

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("driver_departure_test");
        postgres.start();
        context = new AnnotationConfigApplicationContext(Config.class);
        sql = new JdbcTemplate(context.getBean(DataSource.class));
        service = context.getBean(DriverService.class);
        principal = context.getBean(CurrentUserService.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void stop() {
        if (context != null) context.close();
        if (postgres != null) postgres.stop();
    }
    @BeforeEach void fixtures() {
        // These statements can only reach the newly created disposable container.
        sql.execute("TRUNCATE companies RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Test 1','T1','one@example.test','ACTIVE'),(2,'C2','Test 2','T2','two@example.test','ACTIVE')");
        java.time.OffsetDateTime stamp = java.time.OffsetDateTime.parse("2026-11-09T00:00:00Z");
        for (long id : new long[]{8,9}) sql.update(
                "INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(?,1,?,?,?,'DRIVER','ACTIVE',?,?)",
                id,"test-driver-"+id,id+"@example.test","Fixture driver",stamp,stamp);
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Test box',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Test truck','TEST-001','AVAILABLE')");
        insertTrip(10,1,8,"LOADING");
        sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status) VALUES(40,10,'test-job-40','COMPLETED')");
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(50,40,true,false,1)");
        sql.update("INSERT INTO loading_executions(id,load_plan_id,status) VALUES(60,50,'COMPLETED')");
        sql.update("INSERT INTO delivery_requirements(id,company_id,trip_id,destination,deadline,priority,status,created_at) VALUES(70,1,10,'Fixture destination',localtimestamp,'NORMAL','ASSIGNED',localtimestamp)");
        reset(principal);
        when(principal.getCurrentUser()).thenReturn(User.builder().id(8L).userRoleType(UserRole.DRIVER)
                .company(Company.builder().id(1L).build()).build());
    }
    static void insertTrip(long id,long company,long driver,String status) {
        sql.update("INSERT INTO trips(id,company_id,driver_user_id,created_by_dispatcher_id,vehicle_id,status) VALUES(?,?,?,8,1,?)",id,company,driver,status);
    }
    static String status() { return sql.queryForObject("SELECT status FROM trips WHERE id=10",String.class); }
    static int transitions() { return sql.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id='10' AND action_type='TRIP_STATUS_TRANSITION'",Integer.class); }

    @Test void duplicateConcurrentStartCommitsOneTransitionAndRequirements() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> { gate.await(); try { service.startTrip(10L); return true; } catch(AppException expected) { return false; } };
        try {
            Future<Boolean> a = pool.submit(attempt), b = pool.submit(attempt);
            gate.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS), b.get(20,TimeUnit.SECONDS));
            assertEquals("IN_TRANSIT",status());
            assertEquals(1,transitions());
            assertEquals("IN_TRIP",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
            assertThrows(AppException.class,()->service.startTrip(10L));
            assertEquals(1,transitions());
        } finally { pool.shutdownNow(); }
    }

    @Test void departureWaitsForUncommittedCompletionAndReadsItAfterCommit() throws Exception {
        sql.update("UPDATE loading_executions SET status='IN_PROGRESS' WHERE id=60");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (var conn = context.getBean(DataSource.class).getConnection()) {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("UPDATE loading_executions SET status='COMPLETED' WHERE id=60");
            Future<?> started = pool.submit(()->service.startTrip(10L));
            awaitExecutionLock();
            assertFalse(started.isDone());
            conn.commit();
            started.get(20,TimeUnit.SECONDS);
            assertEquals("IN_TRANSIT",status());
            assertEquals(1,transitions());
        } finally { pool.shutdownNow(); }
    }

    @Test void uncommittedCompletionRollbackCannotAuthorizeDeparture() throws Exception {
        sql.update("UPDATE loading_executions SET status='IN_PROGRESS' WHERE id=60");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (var conn = context.getBean(DataSource.class).getConnection()) {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("UPDATE loading_executions SET status='COMPLETED' WHERE id=60");
            Future<?> started = pool.submit(()->service.startTrip(10L));
            awaitExecutionLock(); conn.rollback();
            ExecutionException error = assertThrows(ExecutionException.class,()->started.get(20,TimeUnit.SECONDS));
            assertInstanceOf(AppException.class,error.getCause());
            assertEquals("LOADING",status()); assertEquals(0,transitions());
            assertEquals("ASSIGNED",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void oldCompletedPlanDoesNotAuthorizeNewExecution() {
        sql.update("UPDATE load_plans SET is_approved=false WHERE id=50");
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(51,40,true,false,1)");
        sql.update("INSERT INTO loading_executions(id,load_plan_id,status) VALUES(61,51,'IN_PROGRESS')");
        assertFalse(service.listTrips(null).getFirst().getLoadingCompleted());
        assertThrows(AppException.class,()->service.startTrip(10L));
        assertEquals("LOADING",status()); assertEquals(0,transitions());
    }

    @Test void tenantQueryKeepsOwnTransitAndHistoryButExcludesForeignAssignments() {
        insertTrip(11,2,8,"LOADING");
        insertTrip(12,1,8,"IN_TRANSIT");
        insertTrip(13,1,8,"DELIVERED");
        insertTrip(14,1,9,"LOADING");
        var rows = service.listTrips(null);
        assertEquals(Set.of(10L,12L,13L),rows.stream().map(r->r.getTripId()).collect(java.util.stream.Collectors.toSet()));
        assertThrows(AppException.class,()->service.startTrip(11L));
        assertThrows(AppException.class,()->service.startTrip(14L));
    }

    static void awaitExecutionLock() throws Exception {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        do {
            Integer waiting = sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%loading_executions%'",Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        } while (System.nanoTime()<until);
        fail("Departure did not reach the expected execution row lock");
    }
}
