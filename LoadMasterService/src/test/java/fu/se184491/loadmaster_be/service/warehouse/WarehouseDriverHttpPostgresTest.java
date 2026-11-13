package fu.se184491.loadmaster_be.service.warehouse;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.controller.warehouse.*;
import fu.se184491.loadmaster_be.controller.driver.DriverController;
import fu.se184491.loadmaster_be.controller.optimization.LoadPlanController;
import fu.se184491.loadmaster_be.config.security.FlaggedUserEnforcementFilter;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestPackageRepository;
import fu.se184491.loadmaster_be.service.warehouse.Impl.*;
import fu.se184491.loadmaster_be.service.driver.Impl.*;
import fu.se184491.loadmaster_be.service.trip.*;
import fu.se184491.loadmaster_be.service.monitoring.*;
import fu.se184491.loadmaster_be.service.optimization.*;
import fu.se184491.loadmaster_be.service.optimization.Impl.LoadPlanQueryServiceImpl;
import fu.se184491.loadmaster_be.service.optimization.manual.StopZoneResolver;
import fu.se184491.loadmaster_be.service.pickup.PickupDriverService;
import fu.se184491.loadmaster_be.security.TestJwtSupport;
import jakarta.persistence.EntityManagerFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real loopback HTTP + production controllers/security/services/JPA + fresh random-port PostgreSQL.
 * No SpringApplication/config-file loader, environment datasource, scheduler, optimization job or live issuer.
 * Python approval is an explicit LOCAL HTTP fixture (not Python/solver E2E).
 */
class WarehouseDriverHttpPostgresTest {
    static PostgreSQLContainer postgres;
    static AnnotationConfigWebApplicationContext context;
    static Tomcat tomcat;
    static HttpServer approvalFixture;
    static JdbcTemplate sql;
    static String base;
    static final ObjectMapper json = new ObjectMapper();
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final AtomicInteger approvals = new AtomicInteger();

    @Configuration @EnableWebMvc @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
      excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
        pattern="^(?!.*(?:TripRepository|LoadPlanRepository|LoadingExecutionRepository|UserRepository|AuditLogRepository|DeliveryStopRepository|PackagePlacementRepository|CargoPackageRepository|PlacementConfirmationRepository|DeviationRepository|LoadingDamageRepository|StagingConfirmationRepository|StagingShortageRepository|ManualUnloadConfirmationRepository|UnloadConfirmationRepository|DeliveryIssueRepository|DeliveryRequirementRepository)$).*$"))
    @Import({TestJwtSupport.class, FlaggedUserEnforcementFilter.class, CurrentUserService.class,
      WarehouseController.class, WarehouseStagingController.class, DriverController.class, LoadPlanController.class,
      GlobalExceptionHandler.class, WarehouseServiceImpl.class, WarehouseSessionLookup.class, WarehouseProgress.class,
      WarehouseStagingServiceImpl.class, WarehouseIncidentServiceImpl.class, StagingRecorder.class,
      DriverServiceImpl.class, DriverDeliveryServiceImpl.class, TripDepartureReadiness.class, TripStatusService.class,
      TripLookupHelper.class, TripPackageResolver.class, LoadPlanQueryServiceImpl.class, LoadPlanReadRepository.class,
      StopZoneResolver.class})
    static class Config {
        @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
        @Bean DataSource dataSource() {
            var uri=URI.create(postgres.getJdbcUrl().substring(5));
            if (!"/warehouse_driver_step6_test".equals(uri.getPath()) || uri.getPort()==5432 ||
                uri.getPort()!=postgres.getMappedPort(5432) || !Set.of("localhost","127.0.0.1").contains(uri.getHost()))
                throw new IllegalStateException("Only a fresh local test container is permitted");
            return new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds,
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            var f=new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(ds); f.setPackagesToScan("fu.se184491.loadmaster_be.entity");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","hibernate.show_sql","false",
                "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
            return f;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
        @Bean NamedParameterJdbcTemplate namedJdbc(DataSource d){return new NamedParameterJdbcTemplate(d);}
        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr() {
            var qr=mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);
            when(qr.encrypt(any())).thenAnswer(a->a.getArgument(0));
            when(qr.decrypt(any())).thenAnswer(a->a.getArgument(0));
            return qr;
        }
        @Bean PickupRequestPackageRepository pickupPackages(){return mock(PickupRequestPackageRepository.class);}
        @Bean PickupDriverService pickups(){return mock(PickupDriverService.class);}
        @Bean GpsTrackingService gps(){return mock(GpsTrackingService.class);}
        @Bean TripExceptionService tripExceptions(){return mock(TripExceptionService.class);}
        @Bean EtaCalculationService eta(){return mock(EtaCalculationService.class);}
        @Bean fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository gpsRepo(){return mock(fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository.class);}
        @Bean fu.se184491.loadmaster_be.repository.route.RoutePlanRepository routes(){return mock(fu.se184491.loadmaster_be.repository.route.RoutePlanRepository.class);}
        @Bean LoadingReplanService replan(){return mock(LoadingReplanService.class);}
        @Bean OptimizationAccess optimizationAccess(){return mock(OptimizationAccess.class);}
        @Bean LoadPlanService unusedPlanMutations(){return mock(LoadPlanService.class);}
        @Bean OptimizationClient optimizationClient() {
            var tokens=mock(fu.se184491.loadmaster_be.service.security.ServiceTokenProvider.class);
            when(tokens.getOptimizationServiceToken()).thenReturn("local-approval-fixture-only");
            return new OptimizationClient(RestClient.builder().baseUrl("http://127.0.0.1:"+approvalFixture.getAddress().getPort()).build(),tokens);
        }
    }

    @BeforeAll static void startEnvironment() throws Exception {
        postgres=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("warehouse_driver_step6_test");
        postgres.start();
        approvalFixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        approvalFixture.createContext("/", exchange -> {
            int status=500;
            try {
                if (!exchange.getRequestMethod().equals("POST") ||
                    !exchange.getRequestURI().getPath().equals("/api/v1/load-plans/50/approve"))
                    throw new IllegalArgumentException("Unexpected upstream request");
                var body=json.readTree(exchange.getRequestBody());
                if(body.path("approved_by_user_id").asLong()!=7) throw new IllegalArgumentException("Wrong approver");
                approvals.incrementAndGet();
                // This is the documented upstream fixture boundary, not production Python approval.
                int updated=sql.update("UPDATE load_plans SET is_approved=true,approved_by=7 WHERE id=50 AND is_approved=false AND is_stale=false");
                if(updated!=1) throw new IllegalStateException("Approval fixture precondition");
                status=200;
            } catch(Exception ignored) { /* Return failure; never print request headers/tokens. */ }
            byte[] payload=("{\"success\":"+(status==200)+",\"data\":{}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        approvalFixture.start();
        Path dir=Files.createTempDirectory(Path.of("target"),"warehouse-step6-http-");
        tomcat=new Tomcat(); tomcat.setBaseDir(dir.toString()); tomcat.setHostname("127.0.0.1"); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");
        var web=tomcat.addContext("",dir.toAbsolutePath().toString());
        web.setParentClassLoader(WarehouseDriverHttpPostgresTest.class.getClassLoader());
        context=new AnnotationConfigWebApplicationContext();
        // No application.yml/.env/config import; remove system environment/property sources as an additional guard.
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.setServletContext(web.getServletContext()); context.register(Config.class); context.refresh();
        sql=new JdbcTemplate(context.getBean(DataSource.class));
        Tomcat.addServlet(web,"dispatcher",new DispatcherServlet(context)).setLoadOnStartup(1);
        web.addServletMappingDecoded("/","dispatcher");
        FilterDef security=new FilterDef(); security.setFilterName("security");
        security.setFilter(new DelegatingFilterProxy("springSecurityFilterChain",context)); web.addFilterDef(security);
        FilterMap securityMap=new FilterMap(); securityMap.setFilterName("security"); securityMap.addURLPattern("/*"); web.addFilterMap(securityMap);
        FilterDef view=new FilterDef(); view.setFilterName("entityView"); view.setFilter(new OpenEntityManagerInViewFilter()); web.addFilterDef(view);
        FilterMap viewMap=new FilterMap(); viewMap.setFilterName("entityView"); viewMap.addURLPattern("/*"); web.addFilterMap(viewMap);
        web.getServletContext().setAttribute(org.springframework.web.context.WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE,context);
        tomcat.start();
        base="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
        assertNotEquals(5432,postgres.getMappedPort(5432));
        System.out.println("STEP6 isolated HTTP="+base+" DB="+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/warehouse_driver_step6_test; approval fixture loopback random port");
    }
    @AfterAll static void stop() throws Exception {
        try {if(tomcat!=null){tomcat.stop();tomcat.destroy();}}
        finally {if(context!=null)context.close();if(approvalFixture!=null)approvalFixture.stop(0);if(postgres!=null)postgres.stop();}
    }

    @BeforeEach void fixtures() throws Exception {
        approvals.set(0);
        sql.execute("TRUNCATE companies RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'S6-A','Fixture A','S6-A','a@example.test','ACTIVE'),(2,'S6-B','Fixture B','S6-B','b@example.test','ACTIVE')");
        String stamp=new fu.se184491.loadmaster_be.converter.InstantToStringConverter().convertToDatabaseColumn(Instant.parse("2026-11-11T00:00:00Z"));
        for(long id:new long[]{7,8,9,10,17,18,19,28}){
            String role=Set.of(7L,17L).contains(id)?"DISPATCHER":Set.of(8L,18L,28L).contains(id)?"DRIVER":"WAREHOUSE_WORKER";
            sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'ACTIVE',?,?)",
                id,id>=17 && id!=28?2:1,"step6-"+id,id+"@example.test","Fixture "+id,role,stamp,stamp);
        }
        var data=json.readTree(Files.readString(Path.of("src/test/resources/warehouse-step6/plan.json")));
        var v=data.path("vehicle");
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable,door_width,door_height,front_axle_x,rear_axle_x,front_axle_limit_kg,rear_axle_limit_kg,empty_front_axle_load_kg,empty_rear_axle_load_kg) VALUES(1,1,'Step6 fixture',?,?,?,?,?,false,?,?,?,?,?,?,?,?)",
            v.path("inner_l").asInt(),v.path("inner_w").asInt(),v.path("inner_h").asInt(),v.path("max_payload_kg").asInt(),v.path("max_cog_offset_ratio").asDouble(),200,200,100,300,2000,2000,1000,1000);
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(2,2,'Fixture B',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Fixture A','STEP6-A','AVAILABLE'),(2,2,2,'Fixture B','STEP6-B','AVAILABLE')");
        sql.update("INSERT INTO vehicle_type_obstacles(vehicle_type_id,name,type,x,y,z,length,width,height,load_bearing) VALUES(1,'Fixture wheel well','WHEEL_ARCH',300,0,0,40,20,30,false)");
        sql.update("INSERT INTO trips(id,company_id,driver_user_id,created_by_dispatcher_id,vehicle_id,status,trip_code) VALUES(10,1,8,7,1,'PLANNED','STEP6-A'),(20,2,18,17,2,'PLANNED','STEP6-B')");
        sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status,algorithm_name) VALUES(40,10,'60000000-0000-0000-0000-000000000040','COMPLETED','EP_DBLF'),(42,20,'60000000-0000-0000-0000-000000000042','COMPLETED','EP_DBLF')");
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version,packed_items_count,cog_x,cog_y,cog_z) VALUES(50,40,false,false,1,2,200,100,10),(52,42,true,false,1,1,200,100,10)");
        sql.update("INSERT INTO delivery_requirements(id,company_id,trip_id,destination,deadline,priority,status,created_at) VALUES(70,1,10,'Fixture A',localtimestamp+'1 day'::interval,'NORMAL','ASSIGNED',localtimestamp),(80,2,20,'Fixture B',localtimestamp+'1 day'::interval,'NORMAL','ASSIGNED',localtimestamp)");
        sql.update("INSERT INTO delivery_stops(id,trip_id,delivery_requirement_id,stop_sequence,stop_name,status) VALUES(30,10,70,1,'Fixture A','PENDING'),(40,20,80,1,'Fixture B','PENDING')");
        for(var pkg:data.path("packages")){
            int id=pkg.path("id").asInt();
            sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status,actual_length,actual_width,actual_height,actual_weight_kg) VALUES(?,1,?,?,'STANDARD',false,'PLANNED',20,20,20,100)",id,"PK-"+id,"QR-"+id);
            sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(70,?)",id);
        }
        sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status) VALUES(4,2,'PK-4','QR-4','STANDARD',false,'PLANNED')");
        sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(80,4)");
        for(var p:data.path("placements")){
            int id=p.path("package_id").asInt();
            sql.update("INSERT INTO package_placements(id,load_plan_id,package_id,stop_zone_id,pos_x,pos_y,pos_z,packed_length,packed_width,packed_height,rotation_type,step_sequence,pinned) VALUES(?,50,?,30,?,?,?,20,20,20,0,?,false)",
                70+id,id,p.path("x").asInt(),p.path("y").asInt(),p.path("z").asInt(),p.path("step_sequence").asInt());
        }
        sql.update("INSERT INTO package_placements(id,load_plan_id,package_id,stop_zone_id,pos_x,pos_y,pos_z,packed_length,packed_width,packed_height,rotation_type,step_sequence,pinned) VALUES(74,52,4,40,180,90,0,20,20,20,0,1,false)");
    }

    record Reply(int status,JsonNode body){JsonNode data(){return body.path("data");}}
    static Reply call(String method,String path,long user,String role,String body) throws Exception {
        String token=user<0?null:TestJwtSupport.userToken("step6-"+user,role);
        return request(method,path,token,body);
    }
    static Reply request(String method,String path,String token,String body)throws Exception{
        var req=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)req.header("Authorization","Bearer "+token);
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var res=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(),res.body().isBlank()?json.createObjectNode():json.readTree(res.body()));
    }
    static Reply warehouse(String method,String path,String body)throws Exception{return call(method,"/api/warehouse"+path,9,"WAREHOUSE_WORKER",body);}
    static JsonNode ok(Reply r){assertEquals(200,r.status(),r.body().toString());assertTrue(r.body().path("success").asBoolean(),r.body().toString());return r.data();}
    static void blocked(Reply r){assertTrue(r.status()==400||r.status()==403||r.status()==404||r.status()==409,r.body().toString());}
    static int count(String table){return sql.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    static String tripStatus(){return sql.queryForObject("SELECT status FROM trips WHERE id=10",String.class);}
    static void approve()throws Exception{ok(call("POST","/api/load-plans/50/approve",7,"DISPATCHER",null));}
    static long start()throws Exception{approve();return ok(warehouse("POST","/tasks/10/start","{\"staging\":true}")).path("executionId").asLong();}
    static void stage()throws Exception{for(int id=1;id<=2;id++)ok(warehouse("POST","/tasks/10/staging/packages/"+id+"/confirm","{\"method\":\"CODE\",\"code\":\"PK-"+id+"\"}"));}
    static Reply confirm(int id,long trip,String code)throws Exception{return warehouse("POST","/placements/"+id+"/confirm","{\"tripId\":"+trip+",\"method\":\"CODE\",\"code\":\""+code+"\"}");}
    static void handled()throws Exception{ok(confirm(71,10,"PK-1"));ok(confirm(72,10,"PK-2"));}
    static JsonNode driverTrips()throws Exception{return ok(call("GET","/api/driver/trips",8,"DRIVER",null));}

    @Test void httpWorkflowPreservesIdentitiesAndPersistedStates()throws Exception{
        assertEquals(0,count("loading_executions"));assertEquals("PLANNED",tripStatus());
        approve(); assertEquals(1,approvals.get());
        assertTrue(sql.queryForObject("SELECT is_approved FROM load_plans WHERE id=50",Boolean.class));
        assertEquals(7L,sql.queryForObject("SELECT approved_by FROM load_plans WHERE id=50",Long.class));
        var preview=ok(warehouse("GET","/tasks/10/plan",null));
        assertEquals(50,preview.path("loadPlanId").asInt());assertTrue(preview.path("executionId").isNull());
        assertEquals("NOT_STARTED",preview.path("status").asText());assertEquals(1,preview.path("obstacles").size());
        long execution=ok(warehouse("POST","/tasks/10/start","{\"staging\":true}")).path("executionId").asLong();
        assertTrue(execution>0);assertEquals("LOADING",tripStatus());
        assertEquals("IN_PROGRESS",sql.queryForObject("SELECT status FROM loading_executions WHERE id=?",String.class,execution));
        assertFalse(driverTrips().get(0).path("loadingCompleted").asBoolean());
        blocked(call("POST","/api/driver/trips/10/start",8,"DRIVER",null));
        blocked(confirm(71,10,"PK-1"));stage();
        var staged=ok(warehouse("GET","/tasks/10",null));assertTrue(staged.path("stagingComplete").asBoolean());
        blocked(confirm(72,10,"PK-2"));blocked(confirm(71,10,"PK-2"));
        var first=ok(confirm(71,10," pk-1 "));assertEquals(71,first.path("placementId").asInt());
        assertEquals(72,first.path("nextPlacement").path("placementId").asInt());
        ok(confirm(72,10,"PK-2"));
        var read=ok(warehouse("GET","/tasks/10",null));
        assertEquals(2,read.path("confirmedCount").asInt());assertEquals(execution,read.path("executionId").asLong());
        assertEquals(50,read.path("loadPlanId").asInt());assertEquals(10,read.path("tripId").asInt());
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM placement_confirmations c JOIN package_placements p ON c.placement_id=p.id JOIN load_plans l ON p.load_plan_id=l.id JOIN optimization_jobs j ON l.job_id=j.id JOIN trips t ON j.trip_id=t.id JOIN packages pk ON pk.id=p.package_id WHERE t.id=10 AND t.company_id=1 AND pk.company_id=1 AND l.id=50 AND p.id IN(71,72) AND pk.id IN(1,2)",Integer.class));
        var completed=ok(warehouse("POST","/tasks/10/complete",null));
        assertEquals("COMPLETED",completed.path("status").asText());assertEquals("LOADING",completed.path("tripStatus").asText());
        assertEquals("LOADING",tripStatus());assertTrue(driverTrips().get(0).path("loadingCompleted").asBoolean());
        blocked(warehouse("POST","/tasks/10/complete",null));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='LOADING_COMPLETED'",Integer.class));
        var departed=ok(call("POST","/api/driver/trips/10/start",8,"DRIVER",null));
        assertEquals("IN_TRANSIT",departed.path("status").asText());assertEquals("IN_TRANSIT",tripStatus());
        assertEquals("IN_TRIP",sql.queryForObject("SELECT status FROM delivery_requirements WHERE id=70",String.class));
        blocked(call("POST","/api/driver/trips/10/start",8,"DRIVER",null));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='TRIP_STATUS_TRANSITION' AND new_values->>'status'='IN_TRANSIT'",Integer.class));
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='WAREHOUSE_CONFIRM' AND company_id=1",Integer.class));
        System.out.println("STEP6 workflow: trip=10 plan=50 execution="+execution+" company=1 packages=1,2 placements=71,72; terminal=IN_TRANSIT; 2 confirmations, 1 completion, 1 departure; requirement=IN_TRIP");
    }

    @Test void unauthenticatedWrongRoleAudienceAndTenantFailClosed()throws Exception{
        assertEquals(401,warehouseAnonymous().status());
        // Existing /api/driver/** matcher is permitAll, but controller @PreAuthorize denies anonymous with 403.
        // Assert the actual policy plus no mutation, rather than assuming the warehouse filter's 401 here.
        assertEquals(403,call("POST","/api/driver/trips/10/start",-1,"DRIVER",null).status());
        assertEquals("PLANNED",tripStatus());assertEquals(0,count("loading_executions"));
        assertEquals(403,request("GET","/api/warehouse/tasks",TestJwtSupport.serviceToken("loadmaster-api","optimization-service","service-account-optimization-service",List.of("OPTIMIZATION_CREDIT")),null).status());
        assertEquals(403,call("GET","/api/warehouse/tasks",8,"DRIVER",null).status());
        String wrongAud=TestJwtSupport.token("wrong-audience","loadmaster-web","step6-9","step6-9",List.of("WAREHOUSE_WORKER"));
        assertEquals(401,request("GET","/api/warehouse/tasks",wrongAud,null).status());
        blocked(call("GET","/api/warehouse/tasks/20/plan",9,"WAREHOUSE_WORKER",null));
        blocked(call("GET","/api/load-plans/52",7,"DISPATCHER",null));
        blocked(call("POST","/api/load-plans/50/approve",17,"DISPATCHER",null));assertEquals(0,approvals.get());
        start();stage();
        blocked(call("POST","/api/warehouse/placements/71/confirm",19,"WAREHOUSE_WORKER","{\"tripId\":10,\"method\":\"CODE\",\"code\":\"PK-1\"}"));
        blocked(confirm(74,10,"PK-4"));blocked(confirm(71,20,"PK-1"));blocked(confirm(999999,10,"PK-1"));
        blocked(call("POST","/api/driver/trips/10/start",18,"DRIVER",null));
        blocked(call("POST","/api/driver/trips/10/start",28,"DRIVER",null));
        assertEquals(0,count("placement_confirmations"));
    }
    static Reply warehouseAnonymous()throws Exception{return request("GET","/api/warehouse/tasks",null,null);}
    @ParameterizedTest @ValueSource(strings={"COMPLETED","ABORTED"})
    void closedExecutionCannotConfirm(String state)throws Exception{
        long id=start();stage();sql.update("UPDATE loading_executions SET status=? WHERE id=?",state,id);
        blocked(confirm(71,10,"PK-1"));assertEquals(0,count("placement_confirmations"));
    }
    @Test void stalePlanCannotConfirmOrComplete()throws Exception{
        start();stage();sql.update("UPDATE load_plans SET is_stale=true WHERE id=50");
        blocked(warehouse("GET","/tasks/10/plan",null));blocked(confirm(71,10,"PK-1"));
        blocked(warehouse("POST","/tasks/10/complete",null));assertEquals(0,count("placement_confirmations"));
    }
    @Test void stalePlannedPlanCannotStartOrCreateExecution()throws Exception{
        approve();sql.update("UPDATE load_plans SET is_stale=true WHERE id=50");
        blocked(warehouse("POST","/tasks/10/start","{\"staging\":true}"));assertEquals(0,count("loading_executions"));
    }
    @Test void foreignPackageInOwnPlacementRejected()throws Exception{
        start();stage();sql.update("UPDATE package_placements SET package_id=4 WHERE id=71");
        blocked(confirm(71,10,"PK-4"));assertEquals(0,count("placement_confirmations"));
    }
    @Test void pendingAndEmptyAndUnstagedCannotComplete()throws Exception{
        start();blocked(warehouse("POST","/tasks/10/complete",null));stage();
        blocked(warehouse("POST","/tasks/10/complete",null));ok(confirm(71,10,"PK-1"));
        blocked(warehouse("POST","/tasks/10/complete",null));assertEquals("LOADING",tripStatus());
        sql.update("DELETE FROM staging_confirmations");sql.update("DELETE FROM placement_confirmations");sql.update("DELETE FROM package_placements WHERE load_plan_id=50");
        blocked(warehouse("POST","/tasks/10/complete",null));assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='LOADING_COMPLETED'",Integer.class));
    }
    @Test void manualPendingBlocksAndDeviationDamageKeepExistingSemantics()throws Exception{
        start();stage();
        ok(warehouse("POST","/placements/71/manual?tripId=10","{\"reason\":\"LABEL_DAMAGED\"}"));
        ok(confirm(72,10,"PK-2"));blocked(warehouse("POST","/tasks/10/complete",null));
        assertFalse(driverTrips().get(0).path("loadingCompleted").asBoolean());assertEquals(1,count("manual_unload_confirmations"));
    }
    @Test void deviationAndDamagePersistAndAllowCompletionWithActualLoadedPackage()throws Exception{
        start();stage();
        ok(warehouse("POST","/placements/71/deviation","{\"tripId\":10,\"actualPosX\":180,\"actualPosY\":90,\"actualPosZ\":0,\"reason\":\"Fixture position\"}"));
        ok(warehouse("POST","/placements/72/damaged?tripId=10",null));
        var state=ok(warehouse("GET","/tasks/10",null));assertEquals("DEVIATION",state.path("placements").get(0).path("status").asText());
        assertEquals("DAMAGED",state.path("placements").get(1).path("status").asText());
        ok(warehouse("POST","/tasks/10/complete",null));assertEquals(1,count("deviations"));assertEquals(1,count("loading_damages"));assertEquals("LOADING",tripStatus());
    }
    @Test void twoWarehouseUsersConcurrentAndAckIgnoredHaveOneConfirmation()throws Exception{
        start();stage();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            Callable<Reply> a=()->{gate.await();return call("POST","/api/warehouse/placements/71/confirm",9,"WAREHOUSE_WORKER","{\"tripId\":10,\"method\":\"CODE\",\"code\":\"PK-1\"}");};
            Callable<Reply> b=()->{gate.await();return call("POST","/api/warehouse/placements/71/confirm",10,"WAREHOUSE_WORKER","{\"tripId\":10,\"method\":\"CODE\",\"code\":\"PK-1\"}");};
            var aa=pool.submit(a);var bb=pool.submit(b);gate.countDown();
            var ra=aa.get(25,TimeUnit.SECONDS);var rb=bb.get(25,TimeUnit.SECONDS);
            assertEquals(1,(ra.status()==200?1:0)+(rb.status()==200?1:0));
            blocked(ra.status()==200?rb:ra);
            // Discard winning ACK and recover by GET; no automatic POST retry.
            assertEquals("CONFIRMED",ok(warehouse("GET","/tasks/10",null)).path("placements").get(0).path("status").asText());
            assertEquals(1,count("placement_confirmations"));
        }finally{pool.shutdownNow();}
    }
    @Test void oldCompletedPlanCannotAuthorizeCurrentExecutionAndDriverListIsTenantScoped()throws Exception{
        long id=start();
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(49,40,true,false,0)");
        sql.update("INSERT INTO loading_executions(load_plan_id,status,staging_required) VALUES(49,'COMPLETED',false)");
        assertFalse(driverTrips().get(0).path("loadingCompleted").asBoolean());
        blocked(call("POST","/api/driver/trips/10/start",8,"DRIVER",null));
        sql.update("INSERT INTO trips(id,company_id,driver_user_id,created_by_dispatcher_id,vehicle_id,status) VALUES(11,1,8,7,1,'IN_TRANSIT'),(12,1,8,7,1,'DELIVERED'),(13,1,28,7,1,'LOADING')");
        Set<Integer> visible=new HashSet<>();for(var row:driverTrips())visible.add(row.path("tripId").asInt());
        assertEquals(Set.of(10,11,12),visible);
        blocked(call("GET","/api/driver/trips?driverId=18",8,"DRIVER",null));
        assertEquals("IN_PROGRESS",sql.queryForObject("SELECT status FROM loading_executions WHERE id=?",String.class,id));
    }
    @Test void actualWarehouseCompletionAndDriverStartConcurrentRemainConsistent()throws Exception{
        start();stage();handled();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var c=pool.submit(()->{gate.await();return warehouse("POST","/tasks/10/complete",null);});
            var d=pool.submit(()->{gate.await();return call("POST","/api/driver/trips/10/start",8,"DRIVER",null);});
            gate.countDown();ok(c.get(25,TimeUnit.SECONDS));
            var departure=d.get(25,TimeUnit.SECONDS);
            if(departure.status()!=200){blocked(departure);ok(call("POST","/api/driver/trips/10/start",8,"DRIVER",null));}
            assertEquals("IN_TRANSIT",tripStatus());
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='LOADING_COMPLETED'",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action_type='TRIP_STATUS_TRANSITION' AND new_values->>'status'='IN_TRANSIT'",Integer.class));
        }finally{pool.shutdownNow();}
    }
}
