package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.trip.impl.TripServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
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
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real service/JPA/PG transactions. Does not load Boot, application.yml, schedulers or remote configuration. */
class RequirementAssignmentPostgresTest {
    static PostgreSQLContainer postgres;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static TripService service;
    static CurrentUserService principal;
    static TransactionTemplate tx;
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
      excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
        pattern="^(?!.*(?:TripRepository|CargoPackageRepository|DeliveryRequirementRepository|DeliveryStopRepository|CompanyWarehouseRepository|VehicleRepository|UserRepository|RoutePlanRepository|LoadPlanRepository)$).*$"))
    @Import({TripServiceImpl.class, fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility.class,
        fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore.class})
    static class Config {
        @Bean JdbcTemplate presenceJdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSource dataSource() {
            var uri = java.net.URI.create(postgres.getJdbcUrl().substring(5));
            if (!"/requirement_assignment_test".equals(uri.getPath()) || uri.getPort()==5432
                || uri.getPort()!=postgres.getMappedPort(5432)
                || !Set.of("localhost","127.0.0.1").contains(uri.getHost()))
                throw new IllegalStateException("Disposable local container only");
            return new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            var f=new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(ds); f.setPackagesToScan("fu.se184491.loadmaster_be.entity");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","jakarta.persistence.validation.mode","none",
              "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
            return f;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
        @Bean CurrentUserService principal(){return mock(CurrentUserService.class);}
        @Bean TripStatusService transitions(){return mock(TripStatusService.class);}
        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr(){return mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);}
    }
    @BeforeAll static void start(){
        postgres=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("requirement_assignment_test");
        postgres.start();
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.register(Config.class); context.refresh();
        sql=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(TripService.class);
        principal=context.getBean(CurrentUserService.class);
        tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void stop(){if(context!=null)context.close();if(postgres!=null)postgres.stop();}
    @BeforeEach void fixtures(){
        sql.execute("TRUNCATE companies RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture','T1','one@example.test','ACTIVE'),(2,'C2','Fixture2','T2','two@example.test','ACTIVE')");
        String stamp = "2026-11-11 00:00:00"; // Synthetic User LocalDateTime/VARCHAR(19) contract.
        sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(8,1,'fixture','fixture@example.test','Fixture','DISPATCHER','ACTIVE',?,?)",stamp,stamp);
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Fixture',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Fixture','TEST-1','AVAILABLE')");
        sql.update("INSERT INTO trips(id,company_id,created_by_dispatcher_id,vehicle_id,status) VALUES(10,1,8,1,'DRAFT'),(11,1,8,1,'DRAFT')");
        requirement(70); requirement(71);
        reset(principal);
        when(principal.getCurrentCompanyId()).thenReturn(1L);
        when(principal.getCurrentUser()).thenReturn(User.builder().id(8L).company(Company.builder().id(1L).build()).build());
    }
    static void requirement(long id){
        sql.update("INSERT INTO delivery_requirements(id,company_id,destination,deadline,priority,status,created_at,expected_package_count) VALUES(?,1,'Fixture',localtimestamp + interval '1 day','NORMAL','PENDING',localtimestamp,4)",id);
    }
    static void packages(int count){
        for(int i=1;i<=count;i++){
            sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status) VALUES(?,1,?,?,'STANDARD',false,'PENDING')",i,"FIX-"+i,"fixture-"+i);
            sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(70,?)",i);
        }
    }
    static void fails(ErrorCode code){assertEquals(code,assertThrows(AppException.class,()->service.assignRequirementToTrip(10L,70L)).getErrorCode());assertEquals(0,stops());}
    static int stops(){return sql.queryForObject("SELECT count(*) FROM delivery_stops",Integer.class);}
    @ParameterizedTest @ValueSource(ints={0,1,3})
    void incompleteCountsAreAuthoritative(int count){
        packages(count);
        var ex=assertThrows(RequirementPackagesIncompleteException.class,()->service.assignRequirementToTrip(10L,70L));
        assertEquals(Map.of("expectedCount",4,"receivedCount",count,"missingCount",4-count),ex.getCounts());
        assertEquals(0,stops());assertEquals("PENDING",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
    }
    @Test void fourValidPackagesAreAssignedAndPlanned(){
        packages(4);var result=service.assignRequirementToTrip(10L,70L);
        assertEquals(10L,result.tripId());assertEquals(1,stops());
        assertEquals(4,sql.queryForObject("SELECT count(*) FROM packages WHERE status='PLANNED'",Integer.class));
    }
    @Test void linkedCountDoesNotAuthorizeCancelledPackage(){
        packages(4);sql.update("UPDATE packages SET status='CANCELLED' WHERE id=1");
        fails(ErrorCode.REQUIREMENT_PACKAGES_INELIGIBLE);
        assertEquals(4,sql.queryForObject("SELECT count(*) FROM delivery_requirement_packages WHERE delivery_requirement_id=70",Integer.class));
    }
    @Test void invalidTripHasItsOwnError(){packages(4);sql.update("UPDATE trips SET status='LOADING' WHERE id=10");fails(ErrorCode.TRIP_NOT_EDITABLE);}
    @Test void foreignPackageBlocked(){packages(4);sql.update("UPDATE packages SET company_id=2 WHERE id=1");fails(ErrorCode.REQUIREMENT_PACKAGE_COMPANY_MISMATCH);}
    @Test void flaggedPackageBlocked(){packages(4);sql.update("UPDATE packages SET flag='DAMAGED' WHERE id=1");fails(ErrorCode.PACKAGE_FLAGGED);}
    @Test void sharedPackageAlreadyInAnotherTripBlocked(){
        packages(4);sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(71,1)");
        sql.update("UPDATE delivery_requirements SET trip_id=11,status='ASSIGNED' WHERE id=71");
        fails(ErrorCode.REQUIREMENT_PACKAGE_ALREADY_ASSIGNED);
    }
    @Test void companyCannotReadOrAssignOtherTrip(){packages(4);when(principal.getCurrentCompanyId()).thenReturn(2L);fails(ErrorCode.TRIP_NOT_FOUND);}
    @Test void malformedExpectedCountBlocked(){packages(4);sql.update("UPDATE delivery_requirements SET expected_package_count=null WHERE id=70");fails(ErrorCode.REQUIREMENT_PACKAGE_COUNT_INVALID);}
    @Test void duplicateConcurrentAssignmentCommitsOneStop() throws Exception{
        packages(4);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            Callable<Boolean> a=()->{gate.await();try{service.assignRequirementToTrip(10L,70L);return true;}catch(AppException ex){return false;}};
            var first=pool.submit(a);var second=pool.submit(a);gate.countDown();
            assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));assertEquals(1,stops());
        }finally{pool.shutdownNow();}
    }
    @Test void overlappingRequirementsCannotClaimPackageConcurrently() throws Exception{
        packages(4);sql.update("UPDATE delivery_requirements SET expected_package_count=1 WHERE id=71");
        sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(71,1)");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var a=pool.submit(()->attempt(gate,10,70));var b=pool.submit(()->attempt(gate,11,71));gate.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,stops());
        }finally{pool.shutdownNow();}
    }
    static boolean attempt(CountDownLatch gate,long trip,long requirement)throws Exception{
        gate.await();try{service.assignRequirementToTrip(trip,requirement);return true;}catch(AppException ex){return false;}
    }
    @Test void enclosingTransactionRollsBackEarlierAssignmentWhenNextRequirementIncomplete(){
        packages(4);
        assertThrows(RequirementPackagesIncompleteException.class,()->tx.executeWithoutResult(s->{
            service.assignRequirementToTrip(10L,70L); service.assignRequirementToTrip(10L,71L);
        }));
        assertEquals(0,stops());assertEquals(0,sql.queryForObject("SELECT count(*) FROM packages WHERE status='PLANNED'",Integer.class));
    }
    @Test void packageStateIsReadAfterWaitingForPackageLock() throws Exception {
        packages(4);
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=context.getBean(DataSource.class).getConnection()){
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("UPDATE packages SET status='CANCELLED' WHERE id=1");
            var result=pool.submit(()->{
                try{service.assignRequirementToTrip(10L,70L);return (ErrorCode)null;}
                catch(AppException ex){return ex.getErrorCode();}
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            boolean waiting=false;
            while(System.nanoTime()<deadline){
                waiting=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock')",Boolean.class));
                if(waiting)break;
                Thread.sleep(25);
            }
            assertTrue(waiting,"Assignment must reach the held package lock");
            assertFalse(result.isDone());
            connection.commit();
            assertEquals(ErrorCode.REQUIREMENT_PACKAGES_INELIGIBLE,result.get(20,TimeUnit.SECONDS));
            assertEquals(0,stops());
        } finally {pool.shutdownNow();}
    }
    @Test void existingErrorEnvelopeCarriesNumericCounts(){
        var response=new GlobalExceptionHandler().handleAppException(new RequirementPackagesIncompleteException(4,3),new org.springframework.mock.web.MockHttpServletRequest());
        assertEquals(409,response.getStatusCode().value());assertEquals("REQUIREMENT_PACKAGES_INCOMPLETE",response.getBody().getCode());
        assertEquals(Map.of("expectedCount",4,"receivedCount",3,"missingCount",1),response.getBody().getErrors());
    }
}
