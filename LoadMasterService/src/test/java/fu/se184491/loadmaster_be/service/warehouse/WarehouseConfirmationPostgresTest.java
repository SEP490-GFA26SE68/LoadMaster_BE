package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.dto.request.warehouse.*;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.warehouse.*;
import fu.se184491.loadmaster_be.service.warehouse.Impl.*;
import fu.se184491.loadmaster_be.service.trip.*;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only a fresh random-port container; no Boot config, DATABASE_URL, schedulers or shared services. */
class WarehouseConfirmationPostgresTest {
    static PostgreSQLContainer postgres;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static WarehouseService service;
    static WarehouseStagingService staging;
    static WarehouseIncidentService incidents;
    static CurrentUserService principal;
    static TransactionTemplate tx;

    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
        excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
            pattern="^(?!.*(?:TripRepository|LoadPlanRepository|LoadingExecutionRepository|UserRepository|AuditLogRepository|DeliveryStopRepository|PackagePlacementRepository|CargoPackageRepository|PlacementConfirmationRepository|DeviationRepository|LoadingDamageRepository|StagingConfirmationRepository|StagingShortageRepository|ManualUnloadConfirmationRepository)$).*$"))
    @Import({WarehouseServiceImpl.class,WarehouseSessionLookup.class,WarehouseProgress.class,
        WarehouseIncidentServiceImpl.class,WarehouseStagingServiceImpl.class,StagingRecorder.class,TripDepartureReadiness.class,
        fu.se184491.loadmaster_be.service.driver.Impl.DriverDeliveryServiceImpl.class,
        fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility.class,
        fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore.class})
    static class Config {
        @Bean fu.se184491.loadmaster_be.repository.route.RoutePlanRepository routes(){return mock(fu.se184491.loadmaster_be.repository.route.RoutePlanRepository.class);}
        @Bean JdbcTemplate presenceJdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository presenceVehicles(EntityManagerFactory emf){return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository.class);}
        @Bean DataSource dataSource() {
            var target = java.net.URI.create(postgres.getJdbcUrl().substring(5));
            if (!"/warehouse_confirmation_test".equals(target.getPath())
                    || target.getPort()!=postgres.getMappedPort(5432) || target.getPort()==5432)
                throw new IllegalStateException("Fresh test container required");
            return new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds); factory.setPackagesToScan("fu.se184491.loadmaster_be.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create",
                "jakarta.persistence.validation.mode","none","hibernate.show_sql","false",
                "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) {return new JpaTransactionManager(f);}
        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr() {
            var qr=mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);
            when(qr.encrypt(any())).thenAnswer(a->a.getArgument(0));
            when(qr.decrypt(any())).thenAnswer(a->a.getArgument(0));
            return qr;
        }
        @Bean CurrentUserService principal() {return mock(CurrentUserService.class);}
        @Bean TripStatusService tripStatus() {return mock(TripStatusService.class);}
        @Bean TripPackageResolver resolver() {return mock(TripPackageResolver.class);}
        @Bean TripLookupHelper tripLookup() {return mock(TripLookupHelper.class);}
        @Bean LoadingReplanService replan() {return mock(LoadingReplanService.class);}
        @Bean fu.se184491.loadmaster_be.service.driver.DriverService driver() {return mock(fu.se184491.loadmaster_be.service.driver.DriverService.class);}
        @Bean fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository unloads() {return mock(fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository.class);}
        @Bean fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository issues() {return mock(fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository.class);}
        @Bean fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository gps() {return mock(fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository.class);}
        @Bean fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService eta() {return mock(fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService.class);}
    }
    @BeforeAll static void start() {
        postgres=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("warehouse_confirmation_test");
        postgres.start();
        context=new AnnotationConfigApplicationContext(Config.class);
        sql=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(WarehouseService.class);
        staging=context.getBean(WarehouseStagingService.class);
        incidents=context.getBean(WarehouseIncidentService.class);
        principal=context.getBean(CurrentUserService.class);
        tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void stop() {if(context!=null)context.close(); if(postgres!=null)postgres.stop();}
    @BeforeEach void fixture() {
        sql.execute("TRUNCATE companies RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'W1','Fixture 1','W1','one@example.test','ACTIVE'),(2,'W2','Fixture 2','W2','two@example.test','ACTIVE')");
        String stamp=new fu.se184491.loadmaster_be.converter.InstantToStringConverter().convertToDatabaseColumn(Instant.parse("2026-11-11T00:00:00Z"));
        for(long id:new long[]{8,9})sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(?,1,?,?,?,?,'ACTIVE',?,?)",id,"fixture-"+id,id+"@example.test","Fixture",id==8?"DRIVER":"WAREHOUSE_WORKER",stamp,stamp);
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Fixture',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Fixture','TEST-W','AVAILABLE')");
        sql.update("INSERT INTO trips(id,company_id,driver_user_id,created_by_dispatcher_id,vehicle_id,status) VALUES(10,1,8,9,1,'LOADING'),(11,1,8,9,1,'LOADING'),(12,2,8,9,1,'LOADING')");
        for(long id:new long[]{10,11,12}){
            sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status) VALUES(?,?,?,'COMPLETED')",id+30,id,"fixture-job-"+id);
            sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(?,?,true,false,1)",id+40,id+30);
            sql.update("INSERT INTO loading_executions(id,load_plan_id,status,staging_required) VALUES(?,?,'IN_PROGRESS',false)",id+50,id+40);
        }
        sql.update("INSERT INTO delivery_stops(id,trip_id,stop_sequence,stop_name,status) VALUES(30,10,1,'Fixture','PENDING')");
        for(int id=1;id<=4;id++){
            sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status) VALUES(?,?,?,?,'STANDARD',false,'PLANNED')",id,id==4?2:1,"PK-"+id,"QR-"+id);
            sql.update("INSERT INTO package_placements(id,load_plan_id,package_id,stop_zone_id,pos_x,pos_y,pos_z,packed_length,packed_width,packed_height,rotation_type,step_sequence,pinned) VALUES(?,?,?,30,?,0,0,10,10,10,0,?,false)",id+70,id<=2?50:id==3?51:52,id,id*20,id<=2?id:1);
        }
        reset(principal);
        when(principal.getCurrentUser()).thenReturn(User.builder().id(9L).company(Company.builder().id(1L).build()).userRoleType(UserRole.WAREHOUSE_WORKER).build());
        when(principal.getCurrentCompanyId()).thenReturn(1L);
        when(context.getBean(TripLookupHelper.class).requireTrip("10",1L))
            .thenAnswer(a->context.getBean(TripRepository.class).findById(10L).orElseThrow());
    }
    static ConfirmPlacementRequest label(long trip,String code){return new ConfirmPlacementRequest(trip,StagingMethod.CODE,code);}
    static int confirmations(){return sql.queryForObject("SELECT count(*) FROM placement_confirmations",Integer.class);}
    static boolean ready() {
        return Boolean.TRUE.equals(tx.execute(s->context.getBean(TripDepartureReadiness.class).canStart(
            context.getBean(TripRepository.class).findById(10L).orElseThrow(),
            User.builder().id(8L).company(Company.builder().id(1L).build()).userRoleType(UserRole.DRIVER).build())));
    }

    @Test void correctCodeTrimCaseDuplicateReloadAndCompletionKeepDriverContract() {
        assertFalse(ready());
        service.confirmPlacement(71L,null,label(10," pk-1 "));
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        assertEquals(1,confirmations());
        service.confirmPlacement(72L,null,label(10,"PK-2"));
        assertEquals("COMPLETED",service.completeLoading(10L).getStatus());
        assertEquals("LOADING",sql.queryForObject("SELECT status FROM trips WHERE id=10",String.class));
        assertTrue(ready());
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        assertEquals(2,confirmations());
    }
    @ParameterizedTest @ValueSource(strings={"WRONG","71","1","PK-2","PK-3","PK-4"})
    void wrongOrOtherPackageTripCompanyCodeNeverConfirms(String code) {
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,code)));
        assertEquals(0,confirmations());
    }
    @Test void differentTripContextAndForeignCompanyBlocked() {
        assertThrows(AppException.class,()->service.confirmPlacement(73L,null,label(10,"PK-3")));
        assertThrows(AppException.class,()->service.confirmPlacement(74L,null,label(12,"PK-4")));
        assertEquals(0,confirmations());
    }
    @Test void oldPlanAndStalePlanBlocked() {
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(99,40,true,false,2)");
        sql.update("INSERT INTO loading_executions(id,load_plan_id,status) VALUES(99,99,'IN_PROGRESS')");
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        sql.update("UPDATE load_plans SET is_stale=true WHERE id=99");
        assertThrows(AppException.class,()->service.completeLoading(10L));
        assertEquals(0,confirmations());
    }
    @ParameterizedTest @ValueSource(strings={"COMPLETED","ABORTED"})
    void closedExecutionBlocked(String status) {
        sql.update("UPDATE loading_executions SET status=? WHERE id=60",status);
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        assertEquals(0,confirmations());
    }
    @Test void concurrentConfirmRequestsPersistOnlyOneRow() throws Exception {
        race(()->service.confirmPlacement(71L,null,label(10,"PK-1")),
             ()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        assertEquals(1,confirmations());
    }
    @Test void manualAndCodeConcurrentShareSameLock() throws Exception {
        race(()->service.confirmPlacement(71L,null,label(10,"PK-1")),
             ()->incidents.confirmPlacementManually(10L,71L,new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED,null)));
        assertEquals(1,confirmations());
    }
    static void race(Runnable a,Runnable b)throws Exception {
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        Callable<Boolean> aa=()->{gate.await();try{a.run();return true;}catch(AppException rejected){return false;}};
        Callable<Boolean> bb=()->{gate.await();try{b.run();return true;}catch(AppException rejected){return false;}};
        try {var x=pool.submit(aa);var y=pool.submit(bb);gate.countDown();
            assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    @Test void stagingAndEmptyPlanBlockComplete() {
        sql.update("UPDATE loading_executions SET staging_required=true WHERE id=60");
        assertThrows(AppException.class,()->service.completeLoading(10L));
        assertThrows(AppException.class,()->service.confirmPlacement(71L,null,label(10,"PK-1")));
        staging.stage(10L,1L,StagePackageRequest.builder().method(StagingMethod.CODE).code("pk-1").build());
        assertThrows(AppException.class,()->staging.stage(10L,2L,StagePackageRequest.builder().method(StagingMethod.CODE).code("PK-1").build()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM staging_confirmations",Integer.class));
        sql.update("DELETE FROM package_placements WHERE load_plan_id=50");
        assertThrows(AppException.class,()->service.completeLoading(10L));
    }
    @Test void pendingManualBlocksCompletionWithoutChangingTrip() {
        incidents.confirmPlacementManually(10L,71L,new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED,null));
        service.confirmPlacement(72L,null,label(10,"PK-2"));
        assertThrows(AppException.class,()->service.completeLoading(10L));
        assertFalse(ready());
        assertEquals("IN_PROGRESS",sql.queryForObject("SELECT status FROM loading_executions WHERE id=60",String.class));
    }
    @Test void rollbackDoesNotLeaveConfirmationOrLoadedPackage() {
        assertThrows(IllegalStateException.class,()->tx.execute(s->{
            service.confirmPlacement(71L,null,label(10,"PK-1"));
            throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(0,confirmations());
        assertEquals("PLANNED",sql.queryForObject("SELECT status FROM packages WHERE id=1",String.class));
    }
    @Test void duplicateCompleteRequestsCannotCompleteTwice() throws Exception {
        service.confirmPlacement(71L,null,label(10,"PK-1"));
        service.confirmPlacement(72L,null,label(10,"PK-2"));
        race(()->service.completeLoading(10L),()->service.completeLoading(10L));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='LOADING_COMPLETED'",Integer.class));
        assertTrue(ready());
    }
    @Test void deviationManualAndDamageRequireCorrectTripContext() {
        var deviation=RecordDeviationRequest.builder().tripId(11L).actualPosX(1).actualPosY(0).actualPosZ(0).reason("Fixture").build();
        assertThrows(AppException.class,()->service.recordDeviation(71L,deviation));
        assertThrows(AppException.class,()->incidents.confirmPlacementManually(11L,71L,new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED,null)));
        assertThrows(AppException.class,()->incidents.reportDamaged(11L,71L,null));
        assertEquals(0,confirmations());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM deviations",Integer.class));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM loading_damages",Integer.class));
    }
    @Test void manualRejectionAndCompletionSerializeWithoutFalseCompletion() throws Exception {
        incidents.confirmPlacementManually(10L,71L,new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED,null));
        service.confirmPlacement(72L,null,label(10,"PK-2"));
        long id=sql.queryForObject("SELECT id FROM manual_unload_confirmations",Long.class);
        var decisions=context.getBean(fu.se184491.loadmaster_be.service.driver.DriverDeliveryService.class);
        var request=new fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest();
        request.setReason("Fixture wrong package");
        var pool=Executors.newFixedThreadPool(2);
        var mutated=new CountDownLatch(1); var commit=new CountDownLatch(1);
        try {
            var rejection=pool.submit(()->tx.execute(s->{
                decisions.rejectManualConfirmation("10",id,request); mutated.countDown();
                try {if(!commit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture deadline");}
                catch(InterruptedException ex){throw new IllegalStateException(ex);}
                return true;
            }));
            assertTrue(mutated.await(10,TimeUnit.SECONDS));
            var complete=pool.submit(()->{try{service.completeLoading(10L);return true;}catch(AppException | fu.se184491.loadmaster_be.exception.IncompleteLoadingException blocked){return false;}});
            assertThrows(TimeoutException.class,()->complete.get(250,TimeUnit.MILLISECONDS));
            commit.countDown();
            assertTrue(rejection.get(15,TimeUnit.SECONDS));
            assertFalse(complete.get(15,TimeUnit.SECONDS));
            assertEquals("IN_PROGRESS",sql.queryForObject("SELECT status FROM loading_executions WHERE id=60",String.class));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM placement_confirmations WHERE placement_id=71",Integer.class));
            assertFalse(ready());
        } finally {commit.countDown();pool.shutdownNow();}
    }
    @Test void simultaneousManualDecisionsOnlyOneWins() throws Exception {
        incidents.confirmPlacementManually(10L,71L,new ManualUnloadRequest(ManualConfirmReason.LABEL_DAMAGED,null));
        long id=sql.queryForObject("SELECT id FROM manual_unload_confirmations",Long.class);
        var decisions=context.getBean(fu.se184491.loadmaster_be.service.driver.DriverDeliveryService.class);
        var request=new fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest();
        request.setReason("Fixture wrong package");
        race(()->decisions.approveManualConfirmation("10",id),()->decisions.rejectManualConfirmation("10",id,request));
        String decision=sql.queryForObject("SELECT status FROM manual_unload_confirmations WHERE id=?",String.class,id);
        assertTrue(Set.of("APPROVED","REJECTED").contains(decision));
        assertEquals(decision.equals("APPROVED")?1:0,confirmations());
    }
    @Test void schemaHasNoConfirmationUniqueButDoesHaveExecutionUnique() {
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='placement_confirmations'::regclass AND contype='u'",Integer.class));
        assertTrue(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='loading_executions'::regclass AND contype='u'",Integer.class)>0);
    }
}
