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
    @Import({TripDepartureReadiness.class,TripStatusService.class,DriverServiceImpl.class,fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore.class,fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceService.class,fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility.class})
    static class Config {
        @Bean JdbcTemplate presenceJdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository presenceVehicles(EntityManagerFactory emf){
            return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository.class);
        }

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
        try(var in=DriverDeparturePostgresTest.class.getResourceAsStream("/sql/vehicle-current-presence-proposal.sql")) {
            assertNotNull(in);sql.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            try(var transfers=DriverDeparturePostgresTest.class.getResourceAsStream("/sql/vehicle-warehouse-transfers.sql")){assertNotNull(transfers);sql.execute(new String(transfers.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        } catch(java.io.IOException ex){throw new IllegalStateException(ex);}

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
        String stamp = "2026-11-09 00:00:00"; // Explicit synthetic local User timestamp, no timezone conversion.
        for (long id : new long[]{8,9}) sql.update(
                "INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(?,1,?,?,?,'DRIVER','ACTIVE',?,?)",
                id,"test-driver-"+id,id+"@example.test","Fixture driver",stamp,stamp);
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Test box',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Test truck','TEST-001','AVAILABLE')");
        sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(7,1,'fixture-worker','worker@example.test','Worker','WAREHOUSE_WORKER','ACTIVE',?,?)",stamp,stamp);
        sql.update("INSERT INTO company_warehouses(id,company_id,warehouse_name,address,is_default,is_active,created_at) VALUES(101,1,'A1','Synthetic',false,true,clock_timestamp()),(102,1,'A2','Synthetic',false,true,clock_timestamp())");
        sql.update("INSERT INTO warehouse_staff_assignments(user_id,company_id,warehouse_id,active) VALUES(7,1,101,true)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(2,1,1,'Legacy unknown','TEST-002','AVAILABLE')");
        reset(principal);asWorker();
        context.getBean(fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceService.class).checkIn(101,1,UUID.randomUUID());
        insertTrip(10,1,8,"LOADING");
        sql.update("UPDATE trips SET origin_warehouse_id=101 WHERE id=10");

        sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status) VALUES(40,10,'test-job-40','COMPLETED')");
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(50,40,true,false,1)");
        sql.update("INSERT INTO loading_executions(id,load_plan_id,status) VALUES(60,50,'COMPLETED')");
        sql.update("INSERT INTO delivery_requirements(id,company_id,trip_id,destination,deadline,priority,status,created_at) VALUES(70,1,10,'Fixture destination',localtimestamp,'NORMAL','ASSIGNED',localtimestamp)");
        reset(principal);
        when(principal.getCurrentUser()).thenReturn(User.builder().id(8L).userRoleType(UserRole.DRIVER)
                .company(Company.builder().id(1L).build()).build());
    }
    static void asWorker(){when(principal.getCurrentUser()).thenReturn(User.builder().id(7L).company(Company.builder().id(1L).build()).status(fu.se184491.loadmaster_be.constant.account.UserStatus.ACTIVE).userRoleType(UserRole.WAREHOUSE_WORKER).build());}
    static void asDriver(){when(principal.getCurrentUser()).thenReturn(User.builder().id(8L).company(Company.builder().id(1L).build()).userRoleType(UserRole.DRIVER).build());}
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

    @Test void legacyUnknownIsBlockedUntilAssignedWorkerActuallyConfirmsThenStartIsAtomic() {
        sql.update("UPDATE trips SET vehicle_id=2 WHERE id=10");
        sql.update("INSERT INTO delivery_stops(id,trip_id,delivery_requirement_id,stop_sequence,stop_name,status) VALUES(30,10,70,1,'Synthetic stop','PENDING')");
        sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status) VALUES(1,1,'SYNTHETIC-P1','SYNTHETIC-QR1','STANDARD',false,'PLANNED')");
        sql.update("INSERT INTO package_placements(id,load_plan_id,package_id,stop_zone_id,pos_x,pos_y,pos_z,packed_length,packed_width,packed_height,rotation_type,step_sequence,pinned) VALUES(71,50,1,30,20,0,0,10,10,10,0,1,false)");
        var geometry=sql.queryForList("SELECT * FROM package_placements ORDER BY id");
        var plans=sql.queryForList("SELECT * FROM load_plans ORDER BY id");
        var blocked=assertThrows(AppException.class,()->service.startTrip(10L));
        assertEquals("DRIVER_VEHICLE_PRESENCE_UNVERIFIED",blocked.getErrorCode().name());
        assertEquals("LOADING",status());assertEquals("ASSIGNED",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
        assertNull(sql.queryForObject("SELECT departed_at FROM trips WHERE id=10",Object.class));assertEquals(0,transitions());
        asWorker();var presence=context.getBean(fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceService.class);
        var key=UUID.randomUUID();presence.confirmLoadingPresence(101,10,2,key);presence.confirmLoadingPresence(101,10,2,key);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicle_presence_events WHERE vehicle_id=2",Integer.class));
        asDriver();service.startTrip(10L);
        assertEquals("IN_TRANSIT",status());assertEquals(1,transitions());
        assertEquals("IN_TRANSIT",sql.queryForObject("SELECT status FROM vehicle_current_presence WHERE vehicle_id=2",String.class));
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM vehicle_presence_events WHERE vehicle_id=2",Integer.class));
        assertEquals("IN_TRIP",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
        assertNotNull(sql.queryForObject("SELECT departed_at FROM trips WHERE id=10",Object.class));
        assertEquals(geometry,sql.queryForList("SELECT * FROM package_placements ORDER BY id"));
        assertEquals(plans,sql.queryForList("SELECT * FROM load_plans ORDER BY id"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM packages WHERE id=1",Integer.class));
        assertThrows(AppException.class,()->service.startTrip(10L));assertEquals(1,transitions());
    }
    @Test void wrongWarehouseAndOtherActiveTripCannotDepartAndRollbackRequirements() {
        sql.update("UPDATE trips SET origin_warehouse_id=102 WHERE id=10"); // Xe ghi ở kho khác kho xuất phát: nhân viên kho xuất phát xác nhận được xe, nên tài xế nhận mã "chưa xác nhận xe".
        assertEquals("DRIVER_VEHICLE_PRESENCE_UNVERIFIED",assertThrows(AppException.class,()->service.startTrip(10L)).getErrorCode().name());
        sql.update("UPDATE trips SET origin_warehouse_id=101 WHERE id=10");insertTrip(11,1,9,"LOADING");
        assertEquals("DRIVER_VEHICLE_DEPARTURE_BLOCKED",assertThrows(AppException.class,()->service.startTrip(10L)).getErrorCode().name());
        assertEquals("LOADING",status());assertEquals(0,transitions());assertEquals("ASSIGNED",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
    }
    @Test void missingUnapprovedOrStalePlanAndMissingExecutionStayBlocked() {
        for(String column:List.of("is_stale=true","is_approved=false")) {
            sql.update("UPDATE load_plans SET "+column+" WHERE id=50");assertThrows(AppException.class,()->service.startTrip(10L));
            sql.update("UPDATE load_plans SET is_stale=false,is_approved=true WHERE id=50");
        }
        sql.update("DELETE FROM loading_executions WHERE id=60");assertThrows(AppException.class,()->service.startTrip(10L));
        sql.update("DELETE FROM load_plans WHERE id=50");assertThrows(AppException.class,()->service.startTrip(10L));
        assertEquals("LOADING",status());assertEquals(0,transitions());
    }
    @Test void wrongRoleAndCompanyStayBlockedBeforePresenceWrite() {
        asWorker();assertThrows(AppException.class,()->service.startTrip(10L));
        when(principal.getCurrentUser()).thenReturn(User.builder().id(8L).company(Company.builder().id(2L).build()).userRoleType(UserRole.DRIVER).build());
        assertThrows(AppException.class,()->service.startTrip(10L));assertEquals("LOADING",status());assertEquals(0,transitions());
    }
    @Test void failureAfterCheckoutRollsBackTripPresenceLedgerAndRequirementsTogether() {
        sql.execute("ALTER TABLE audit_logs ADD CONSTRAINT synthetic_reject_transition CHECK(action_type<>'TRIP_STATUS_TRANSITION')");
        try {assertThrows(RuntimeException.class,()->service.startTrip(10L));}
        finally {sql.execute("ALTER TABLE audit_logs DROP CONSTRAINT synthetic_reject_transition");}
        assertEquals("LOADING",status());assertEquals(0,transitions());assertEquals("AT_WAREHOUSE",sql.queryForObject("SELECT status FROM vehicle_current_presence WHERE vehicle_id=1",String.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicle_presence_events WHERE vehicle_id=1",Integer.class));
        assertEquals("ASSIGNED",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
        assertNull(sql.queryForObject("SELECT departed_at FROM trips WHERE id=10",Object.class));
    }
    @Test void concurrentPhysicalVerificationAndStartNeverCheckoutUnknownOrRunTwice() throws Exception {
        sql.update("UPDATE trips SET vehicle_id=2 WHERE id=10");
        var actors=new ThreadLocal<User>();
        var driver=User.builder().id(8L).company(Company.builder().id(1L).build()).userRoleType(UserRole.DRIVER).build();
        var worker=User.builder().id(7L).company(Company.builder().id(1L).build()).userRoleType(UserRole.WAREHOUSE_WORKER).status(fu.se184491.loadmaster_be.constant.account.UserStatus.ACTIVE).build();
        when(principal.getCurrentUser()).thenAnswer(i->Optional.ofNullable(actors.get()).orElse(driver));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var confirmed=pool.submit(()->{gate.await();actors.set(worker);try {context.getBean(fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceService.class).confirmLoadingPresence(101,10,2,UUID.randomUUID());return true;}finally {actors.remove();}});
            var started=pool.submit(()->{gate.await();try {service.startTrip(10L);return true;}catch(AppException ex){assertEquals("DRIVER_VEHICLE_PRESENCE_UNVERIFIED",ex.getErrorCode().name());return false;}});
            gate.countDown();assertTrue(confirmed.get(20,TimeUnit.SECONDS));
            if(!started.get(20,TimeUnit.SECONDS)) service.startTrip(10L);
            assertEquals("IN_TRANSIT",status());assertEquals(1,transitions());assertEquals(2,sql.queryForObject("SELECT count(*) FROM vehicle_presence_events WHERE vehicle_id=2",Integer.class));
            assertThrows(AppException.class,()->service.startTrip(10L));
        } finally {pool.shutdownNow();}
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
