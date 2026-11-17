package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.controller.vehicle.VehiclePresenceController;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.*;
import fu.se184491.loadmaster_be.entity.vehicle.*;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.constant.account.*;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.security.TestJwtSupport;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Isolated PostgreSQL + production presence/eligibility/HTTP security; no Boot config or real URLs. */
class VehiclePresencePostgresTest {
 static PostgreSQLContainer pg; static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql; static VehiclePresenceService service; static VehicleDepartureEligibility eligibility;
 static VehiclePresenceStore store; static TransactionTemplate tx; static MockMvc http;
 @Configuration @EnableWebMvc @EnableTransactionManagement
 @Import({fu.se184491.loadmaster_be.controller.company.WarehouseStaffAssignmentController.class,fu.se184491.loadmaster_be.service.company.WarehouseStaffAssignmentService.class,fu.se184491.loadmaster_be.controller.trip.TripController.class,VehiclePresenceController.class,VehiclePresenceService.class,VehiclePresenceStore.class,VehicleDepartureEligibility.class,TestJwtSupport.class,fu.se184491.loadmaster_be.exception.GlobalExceptionHandler.class})
 static class Config {
  @Bean DataSource dataSource(){
   if(pg.getMappedPort(5432)==5432 || !pg.getDatabaseName().equals("vehicle_presence_test"))throw new IllegalStateException("Isolated container required");
   return new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
  }
  @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
  @Bean org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds,org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
   var factory=new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);
   factory.setPackagesToScan("fu.se184491.loadmaster_be.entity");factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
   // No schema generation/validation: this fixture tests presence SQL and shared JDBC/JPA transactions,
   // not the unrelated legacy User timestamp mapping.
   factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","jakarta.persistence.validation.mode","none","hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));return factory;
  }
  @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr(){return mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);}
  @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf){return new org.springframework.orm.jpa.JpaTransactionManager(emf);}
  @Bean jakarta.persistence.EntityManager entityManager(jakarta.persistence.EntityManagerFactory emf){return org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf);}
  @Bean com.fasterxml.jackson.databind.ObjectMapper legacyMapper(){return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();}
  @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
  @Bean VehicleRepository vehicles(){var repo=mock(VehicleRepository.class);when(repo.findByIdAndCompanyId(anyLong(),anyLong())).thenAnswer(i->{long id=i.getArgument(0),company=i.getArgument(1);if(id==5&&company!=2)return Optional.empty();return Optional.of(Vehicle.builder().id(id).company(Company.builder().id(company).companyName("Synthetic").build()).name("V"+id).licensePlate("TEST-"+id).status(VehicleStatus.AVAILABLE).vehicleType(VehicleType.builder().id(1L).name("Test").build()).build());});return repo;}
  @Bean TripRepository trips(JdbcTemplate sql,VehicleRepository vehicles){var repo=mock(TripRepository.class);when(repo.findByIdAndCompanyIdForUpdate(anyLong(),anyLong())).thenAnswer(i->{long id=i.getArgument(0),company=i.getArgument(1);return sql.queryForList("SELECT * FROM trips WHERE id=? AND company_id=?",id,company).stream().findFirst().map(row->Trip.builder().id(id).company(Company.builder().id(company).build()).vehicle(vehicles.findByIdAndCompanyId(((Number)row.get("vehicle_id")).longValue(),company).orElseThrow()).originWarehouse(row.get("origin_warehouse_id")==null?null:CompanyWarehouse.builder().id(((Number)row.get("origin_warehouse_id")).longValue()).build()).status(fu.se184491.loadmaster_be.constant.trip.TripStatus.valueOf((String)row.get("status"))).build());});return repo;}
  @Bean fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository warehouses(JdbcTemplate sql){var repo=mock(fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository.class);when(repo.findByIdAndCompanyId(anyLong(),anyLong())).thenAnswer(i->{long id=i.getArgument(0),company=i.getArgument(1);return sql.queryForList("SELECT * FROM company_warehouses WHERE id=? AND company_id=?",id,company).stream().findFirst().map(row->CompanyWarehouse.builder().id(id).company(Company.builder().id(company).build()).isActive((Boolean)row.get("is_active")).latitude(java.math.BigDecimal.ONE).longitude(java.math.BigDecimal.ONE).build());});return repo;}
  @Bean fu.se184491.loadmaster_be.service.trip.TripService tripService(TripRepository trips,VehicleRepository vehicles,CurrentUserService current,VehicleDepartureEligibility eligibility,fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository warehouses){
   return new fu.se184491.loadmaster_be.service.trip.impl.TripServiceImpl(mock(fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository.class),warehouses,trips,eligibility,vehicles,mock(fu.se184491.loadmaster_be.repository.account.UserRepository.class),current,mock(fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository.class),mock(fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository.class),mock(fu.se184491.loadmaster_be.repository.route.RoutePlanRepository.class),mock(fu.se184491.loadmaster_be.service.trip.TripStatusService.class),mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class));
  }
  @Bean fu.se184491.loadmaster_be.service.trip.TripLifecycleService lifecycle(TripRepository trips,VehicleRepository vehicles,CurrentUserService current,VehicleDepartureEligibility eligibility,fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository warehouses){
   return new fu.se184491.loadmaster_be.service.trip.TripLifecycleService(trips,eligibility,mock(fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository.class),mock(fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository.class),vehicles,mock(fu.se184491.loadmaster_be.repository.account.UserRepository.class),warehouses,mock(fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository.class),mock(fu.se184491.loadmaster_be.repository.common.AuditLogRepository.class),current,mock(fu.se184491.loadmaster_be.service.trip.TripStatusService.class),mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class),mock(fu.se184491.loadmaster_be.service.trip.TripPackageResolver.class),mock(fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository.class),mock(fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository.class),mock(fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository.class));
  }
  @Bean fu.se184491.loadmaster_be.service.trip.TripVehicleChoiceService tripChoices(){return mock(fu.se184491.loadmaster_be.service.trip.TripVehicleChoiceService.class);}

  @Bean CurrentUserService current(){var c=mock(CurrentUserService.class);when(c.getCurrentUser()).thenAnswer(i->actor());when(c.getCurrentCompanyId()).thenAnswer(i->actor().getCompany().getId());return c;}
 }
 static User actor(){var a=SecurityContextHolder.getContext().getAuthentication();String s=a==null?"7":a.getName();long id=Long.parseLong(s);return User.builder().id(id).company(Company.builder().id(id==9 || id==13?2L:1L).build()).status(UserStatus.ACTIVE).userRoleType(id==12 || id==13 || id==14?UserRole.MANAGER:id==8?UserRole.DRIVER:id==11?UserRole.DISPATCHER:UserRole.WAREHOUSE_WORKER).build();}
 @BeforeAll static void start()throws Exception{
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("vehicle_presence_test");pg.start();
  sql=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
  sql.execute("CREATE TABLE users(id bigint PRIMARY KEY, company_id bigint, full_name text, user_role_type text, status text); CREATE TABLE company_warehouses(id bigint PRIMARY KEY,company_id bigint,warehouse_name text,is_active boolean); CREATE TABLE vehicles(id bigint PRIMARY KEY,company_id bigint,name text,license_plate text,status text); CREATE TABLE trips(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,company_id bigint,vehicle_id bigint,status text,origin_warehouse_id bigint,trip_code varchar(50))");
  sql.execute("CREATE TABLE audit_logs(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,user_id bigint REFERENCES users(id),company_id bigint,action_type varchar(50),entity_name varchar(50),entity_id varchar(50),old_values jsonb,new_values jsonb,ip_address varchar(50),created_at timestamptz)");
  // Simulate permissive defaults on NEW tables; the proposal must revoke them.
  sql.execute("CREATE ROLE anon; CREATE ROLE authenticated; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon,authenticated; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO anon,authenticated");
  try(var in=VehiclePresencePostgresTest.class.getResourceAsStream("/sql/vehicle-current-presence-proposal.sql")){assertNotNull(in);sql.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  ctx=new AnnotationConfigWebApplicationContext();ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  sql=ctx.getBean(JdbcTemplate.class); // The same DataSource instance as the transaction manager, not merely the same JDBC URL.
  service=ctx.getBean(VehiclePresenceService.class);eligibility=ctx.getBean(VehicleDepartureEligibility.class);store=ctx.getBean(VehiclePresenceStore.class);tx=new TransactionTemplate(ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
 }
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 @BeforeEach void reset(){
  SecurityContextHolder.clearContext();
  // Test-only full reset of an isolated container, never a shared DB. Append-only triggers stay enabled.
  sql.execute("TRUNCATE audit_logs,vehicle_presence_checkin_requests,vehicle_presence_events,vehicle_current_presence,warehouse_staff_assignments,trips,vehicles,company_warehouses,users RESTART IDENTITY CASCADE");
  sql.execute("INSERT INTO users VALUES(7,1,'Worker A','WAREHOUSE_WORKER','ACTIVE'),(8,1,'Driver A','DRIVER','ACTIVE'),(9,2,'Worker B','WAREHOUSE_WORKER','ACTIVE'),(10,1,'Unassigned','WAREHOUSE_WORKER','ACTIVE'),(11,1,'Dispatcher A','DISPATCHER','ACTIVE'); INSERT INTO company_warehouses VALUES(101,1,'A1',true),(102,1,'A2',true),(201,2,'B1',true); INSERT INTO warehouse_staff_assignments VALUES(7,1,101,true),(9,2,201,true)");
  for(int id=1;id<=6;id++)sql.update("INSERT INTO vehicles VALUES(?,?,?,?,?)",id,id==5?2:1,"V"+id,"TEST-"+id,id==4?"MAINTENANCE":"AVAILABLE");
  tx.executeWithoutResult(t->{store.lockVehicle(1,1);store.change(1,1,101L,"AT_WAREHOUSE",7,"seed:1","CHECK_IN");store.change(2,1,101L,"AT_WAREHOUSE",7,"seed:2","CHECK_IN");store.change(3,1,102L,"AT_WAREHOUSE",7,"seed:3","CHECK_IN");store.change(4,1,101L,"AT_WAREHOUSE",7,"seed:4","CHECK_IN");store.change(5,2,201L,"AT_WAREHOUSE",9,"seed:5","CHECK_IN");});
 }
 String worker(String subject)throws Exception{return "Bearer "+TestJwtSupport.userToken(subject,"WAREHOUSE_WORKER");}
 @Test void T01_T02_T04_T06_choicesAreScopedToPhysicalPresence(){assertEquals(List.of(1L,2L),store.eligibleIds(1,101,null,0,100));assertEquals(List.of(3L),store.eligibleIds(1,102,null,0,100));}
 @Test void T20_absentLegacyPresenceIsUnknown(){assertEquals("UNKNOWN",store.presence(6,1).status());assertNull(store.presence(6,1).warehouseId());}
 @Test void T07_T08_wrongWarehouseAndCompanyRejected(){assertThrows(RuntimeException.class,()->tx.execute(t->eligibility.requireLocked(1,101L,3,null)));assertThrows(RuntimeException.class,()->tx.execute(t->eligibility.requireLocked(1,101L,5,null)));}
 @Test void T09_effectiveWarehouseAndSameVehicleRechecked(){assertThrows(RuntimeException.class,()->tx.execute(t->eligibility.requireLocked(1,102L,1,90L)));sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,1,'LOADING')");assertThrows(RuntimeException.class,()->tx.execute(t->eligibility.requireLocked(1,101L,1,90L)));}
 @Test void T10_checkInAssignedWarehouseAndAudit(){var p=service.checkIn(101,6,UUID.randomUUID());assertEquals("AT_WAREHOUSE",p.status());assertEquals(7L,p.actorId());assertNotNull(p.observedAt());assertEquals(1,store.events(6,1).size());}
 @Test void T11_wrongScopeAndCompanyRejected(){assertThrows(RuntimeException.class,()->service.checkIn(102,6,UUID.randomUUID()));assertThrows(RuntimeException.class,()->service.checkIn(201,6,UUID.randomUUID()));assertThrows(RuntimeException.class,()->service.checkIn(101,5,UUID.randomUUID()));}
 @Test void T12_sameKeyAndNoOpDoNotDuplicateEvents(){var key=UUID.randomUUID();service.checkIn(101,6,key);service.checkIn(101,6,key);service.checkIn(101,6,UUID.randomUUID());assertEquals(1,store.events(6,1).size());assertEquals(2,sql.queryForObject("SELECT count(*) FROM vehicle_presence_checkin_requests WHERE vehicle_id=6",Integer.class));}
 @Test void T12_keyCannotBeReusedForAnotherVehicle(){var key=UUID.randomUUID();service.checkIn(101,6,key);assertThrows(RuntimeException.class,()->service.checkIn(101,1,key));}
 @Test void T13_busyVehicleCannotBeCheckedIn(){sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'IN_TRANSIT')");assertThrows(RuntimeException.class,()->service.checkIn(101,6,UUID.randomUUID()));assertEquals("UNKNOWN",store.presence(6,1).status());}
 @Test void T13_loadingAlsoRejects(){sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'LOADING')");assertThrows(RuntimeException.class,()->service.checkIn(101,6,UUID.randomUUID()));}
 @Test void T14_deliveryDoesNotInventArrival(){sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'DELIVERED')");assertEquals("UNKNOWN",store.presence(6,1).status());}
 @Test void noOrdinaryCheckInTransfersBetweenWarehouses(){assertThrows(RuntimeException.class,()->service.checkIn(101,3,UUID.randomUUID()));assertEquals(102L,store.presence(3,1).warehouseId());}
 Trip departing(){return Trip.builder().id(90L).company(Company.builder().id(1L).build()).vehicle(Vehicle.builder().id(1L).build()).originWarehouse(CompanyWarehouse.builder().id(101L).build()).build();}
 @Test void T17_departureClearsWarehouseAndChoices(){tx.executeWithoutResult(t->service.departure(departing(),8));assertEquals("IN_TRANSIT",store.presence(1,1).status());assertNull(store.presence(1,1).warehouseId());assertFalse(store.eligibleIds(1,101,null,0,100).contains(1L));}
 @Test void receiptRetryAfterDepartureCannotCheckVehicleBackIn(){var key=UUID.randomUUID();service.checkIn(101,1,key);tx.executeWithoutResult(t->service.departure(departing(),8));service.checkIn(101,1,key);assertEquals("IN_TRANSIT",store.presence(1,1).status());}
 @Test void atomicRollbackLeavesNoPartialPresenceOrEvent(){assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{store.lockVehicle(6,1);store.change(6,1,101L,"AT_WAREHOUSE",7,"rollback","CHECK_IN");throw new IllegalStateException("synthetic rollback");}));assertEquals("UNKNOWN",store.presence(6,1).status());assertEquals(0,store.events(6,1).size());}
 @Test void schemaEnforcesTenantAndStateAndAppendOnly(){assertThrows(RuntimeException.class,()->sql.update("UPDATE vehicle_current_presence SET current_warehouse_id=201 WHERE vehicle_id=1"));assertThrows(RuntimeException.class,()->sql.update("UPDATE vehicle_current_presence SET status='IN_TRANSIT' WHERE vehicle_id=1"));assertThrows(RuntimeException.class,()->sql.update("DELETE FROM vehicle_presence_events WHERE vehicle_id=1"));}
 @Test void T19_httpSecurityAndTenant()throws Exception{
  var path="/api/warehouse/vehicle-presence/101/check-in";
  http.perform(post(path).contentType("application/json").header("Idempotency-Key",UUID.randomUUID()).content("{\"vehicleId\":6}")).andExpect(status().isUnauthorized());
  http.perform(post(path).header("Authorization","Bearer "+TestJwtSupport.userToken("8","DRIVER")).contentType("application/json").header("Idempotency-Key",UUID.randomUUID()).content("{\"vehicleId\":6}")).andExpect(status().isForbidden());
  http.perform(post(path).header("Authorization",worker("10")).contentType("application/json").header("Idempotency-Key",UUID.randomUUID()).content("{\"vehicleId\":6}")).andExpect(status().isForbidden());
  http.perform(post(path).header("Authorization",worker("9")).contentType("application/json").header("Idempotency-Key",UUID.randomUUID()).content("{\"vehicleId\":6}")).andExpect(status().isForbidden());
  http.perform(post(path).header("Authorization",worker("7")).contentType("application/json").header("Idempotency-Key",UUID.randomUUID()).content("{\"vehicleId\":6}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AT_WAREHOUSE"));
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles/5/events").header("Authorization",worker("7"))).andExpect(status().isNotFound());
 }
 @Test void wrongAudienceRejected()throws Exception{http.perform(get("/api/warehouse/vehicle-presence/warehouses").header("Authorization","Bearer "+TestJwtSupport.token("wrong","loadmaster-web","7","7",List.of("WAREHOUSE_WORKER")))).andExpect(status().isUnauthorized());}
 @Test void T15_concurrentCheckInAndAllocation()throws Exception{
  var result=race(()->{service.checkIn(101,6,UUID.randomUUID());return true;},()->tx.execute(t->{eligibility.requireLocked(1,101L,6,null);sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'LOADING')");return true;}));
  assertEquals("AT_WAREHOUSE",store.presence(6,1).status());assertTrue(result.getFirst());assertTrue(sql.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=6",Integer.class)<=1);
 }
 @Test void T16_activeAllocationSerializedWithoutChangingDraftPolicy()throws Exception{
  sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,1,'DRAFT'),(1,1,'PLANNED')");
  Callable<Boolean> allocate=()->tx.execute(t->{eligibility.requireLocked(1,101L,1,null);sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,1,'LOADING')");return true;});
  var results=race(allocate,allocate);assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals(1,sql.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=1 AND status='LOADING'",Integer.class));
 }
 @Test void concurrentDuplicateCheckInCreatesOneEvent()throws Exception {
  UUID key=UUID.randomUUID();Callable<Boolean> check=()->{service.checkIn(101,6,key);return true;};
  assertEquals(List.of(true,true),race(check,check));assertEquals(1,store.events(6,1).size());
  assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicle_presence_checkin_requests WHERE vehicle_id=6",Integer.class));
 }
 @Test void concurrentDifferentWarehouseCheckInsCannotBothSucceed()throws Exception {
  sql.update("INSERT INTO warehouse_staff_assignments VALUES(7,1,102,true)");
  var result=race(()->{service.checkIn(101,6,UUID.randomUUID());return true;},()->{service.checkIn(102,6,UUID.randomUUID());return true;});
  assertEquals(1,result.stream().filter(Boolean::booleanValue).count());assertEquals(1,store.events(6,1).size());
 }
 @Test void checkoutAndTripStatusRollBackTogether() {
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status) VALUES(90,1,1,'LOADING')");
  assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{service.departure(departing(),8);sql.update("UPDATE trips SET status='IN_TRANSIT' WHERE id=90");throw new IllegalStateException("synthetic failed status write");}));
  assertEquals("AT_WAREHOUSE",store.presence(1,1).status());assertEquals("LOADING",sql.queryForObject("SELECT status FROM trips WHERE id=90",String.class));assertEquals(1,store.events(1,1).size());
 }
 @Test void concurrentDriverDepartureCannotBeUndoneByStaffCheckIn()throws Exception {
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status) VALUES(90,1,1,'LOADING')");
  var result=race(()->tx.execute(t->{service.departure(departing(),8);sql.update("UPDATE trips SET status='IN_TRANSIT' WHERE id=90");return true;}),()->{service.checkIn(101,1,UUID.randomUUID());return true;});
  assertEquals(List.of(true,false),result);assertEquals("IN_TRANSIT",store.presence(1,1).status());assertEquals(2,store.events(1,1).size());
 }
 @Test void T07_T08_T09_realHttpTripAdmissionRejectsWrongPresenceAndTenant()throws Exception {
  String dispatcher="Bearer "+TestJwtSupport.userToken("11","DISPATCHER");
  http.perform(post("/api/trips").header("Authorization",dispatcher).contentType("application/json").content("{\"tripName\":\"Synthetic\",\"departureTime\":\"2030-01-01T08:00:00\",\"vehicleId\":3,\"originWarehouseId\":101}")).andExpect(status().isConflict());
  http.perform(post("/api/trips").header("Authorization",dispatcher).contentType("application/json").content("{\"tripName\":\"Synthetic\",\"departureTime\":\"2030-01-01T08:00:00\",\"vehicleId\":5,\"originWarehouseId\":101}")).andExpect(status().isNotFound());
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status,origin_warehouse_id) VALUES(90,1,1,'DRAFT',101)");
  http.perform(patch("/api/trips/90").header("Authorization",dispatcher).contentType("application/json").content("{\"originWarehouseId\":102}")).andExpect(status().isConflict());
  assertEquals(101L,sql.queryForObject("SELECT origin_warehouse_id FROM trips WHERE id=90",Long.class));
 }
 @Test void httpSearchIsTenantScopedAndReturnsUtcTimestamps()throws Exception {
  http.perform(get("/api/warehouse/vehicle-presence/101/search").param("query","TEST").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[*].id",org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(5)))).andExpect(jsonPath("$.data[0].observed_at",org.hamcrest.Matchers.endsWith("Z")));
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles/3/events").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
 }
 @Test void jpaWriteAndJdbcPresenceShareTheSameRollbackBoundary() {
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status) VALUES(90,1,1,'LOADING')");
  var em=ctx.getBean("entityManager",jakarta.persistence.EntityManager.class);
  assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{service.departure(departing(),8);em.createNativeQuery("UPDATE trips SET status='IN_TRANSIT' WHERE id=90").executeUpdate();em.flush();throw new IllegalStateException("synthetic JPA failure");}));
  assertEquals("AT_WAREHOUSE",store.presence(1,1).status());assertEquals("LOADING",sql.queryForObject("SELECT status FROM trips WHERE id=90",String.class));assertEquals(1,store.events(1,1).size());
 }
 @Test void publicRestRolesCannotReadOrMutatePresenceTables() {
  for(String table:List.of("warehouse_staff_assignments","vehicle_current_presence","vehicle_presence_events","vehicle_presence_checkin_requests")) {
   assertTrue(sql.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=?::regclass",Boolean.class,table));
   for(String role:List.of("anon","authenticated")) for(String privilege:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE"))
    assertFalse(sql.queryForObject("SELECT has_table_privilege(?::name,?::text,?::text)",Boolean.class,role,table,privilege),role+":"+table+":"+privilege);
  }
  assertFalse(sql.queryForObject("SELECT has_sequence_privilege('anon','vehicle_presence_events_id_seq','USAGE')",Boolean.class));
 }
 @Test void catalogueListsCompanyVehiclesAndPaginatesWithoutWrites()throws Exception {
  int events=sql.queryForObject("SELECT count(*) FROM vehicle_presence_events",Integer.class);
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles").param("size","2").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.last").value(false)).andExpect(jsonPath("$.data[0].id").value(1)).andExpect(jsonPath("$.data[0].current_warehouse_name").value("A1")).andExpect(jsonPath("$.data[0].observed_at",org.hamcrest.Matchers.endsWith("Z")));
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles").param("size","2").param("page","2").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(6)).andExpect(jsonPath("$.data[0].presence_status").value("UNKNOWN")).andExpect(jsonPath("$.data[0].status").value("AVAILABLE")).andExpect(jsonPath("$.last").value(true));
  assertEquals(events,sql.queryForObject("SELECT count(*) FROM vehicle_presence_events",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM vehicle_presence_checkin_requests",Integer.class));
 }
 @Test void catalogueKeepsTenantAssignmentAndRoleGuards()throws Exception {
  String path="/api/warehouse/vehicle-presence/101/vehicles";
  http.perform(get(path)).andExpect(status().isUnauthorized());
  http.perform(get(path).header("Authorization","Bearer "+TestJwtSupport.userToken("11","DISPATCHER"))).andExpect(status().isForbidden());
  for(String id:List.of("9","10")) http.perform(get(path).header("Authorization",worker(id))).andExpect(status().isForbidden());
  sql.update("UPDATE warehouse_staff_assignments SET active=false WHERE user_id=7");http.perform(get(path).header("Authorization",worker("7"))).andExpect(status().isForbidden());
 }
 @Test void catalogueSearchIsLiteralAndDoesNotInferPresenceFromAvailability()throws Exception {
  sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'LOADING')");
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles").param("query","test-6").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].status").value("IN_TRIP")).andExpect(jsonPath("$.data[0].presence_status").value("UNKNOWN"));
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles").param("query","TEST-%").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  http.perform(get("/api/warehouse/vehicle-presence/101/vehicles").param("page","-1").header("Authorization",worker("7"))).andExpect(status().isBadRequest());
 }
 List<Boolean> race(Callable<Boolean> one,Callable<Boolean> two)throws Exception{
  var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
  try{List<Future<Boolean>> out=new ArrayList<>();for(var c:List.of(one,two))out.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{return c.call();}catch(fu.se184491.loadmaster_be.exception.AppException ex){return false;}}));return List.of(out.get(0).get(15,TimeUnit.SECONDS),out.get(1).get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }

 @Test void legacyLoadingUnknownNeedsScopedPhysicalConfirmation() throws Exception {
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status,origin_warehouse_id) VALUES(90,1,6,'LOADING',101)");
  assertEquals("UNKNOWN",store.presence(6,1).status());
  http.perform(post("/api/warehouse/vehicle-presence/101/loading-trips/90/confirm-presence").contentType("application/json").content("{\"vehicleId\":6}")
    .header("Authorization",worker("7")).header("Idempotency-Key",UUID.randomUUID()))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AT_WAREHOUSE"));
  assertEquals("LOADING",sql.queryForObject("SELECT status FROM trips WHERE id=90",String.class));
  assertEquals(1,store.events(6,1).size());assertEquals(7L,store.events(6,1).getFirst().actorId());
 }
 @Test void unknownDepartureReturnsDriverSpecificActionWithoutInventingArrival() {
  var trip=departing();trip.setVehicle(Vehicle.builder().id(6L).build());
  var failure=assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,
   ()->tx.executeWithoutResult(t->service.departure(trip,8)));
  assertEquals("DRIVER_VEHICLE_PRESENCE_UNVERIFIED",failure.getErrorCode().name());
  assertEquals("UNKNOWN",store.presence(6,1).status());assertTrue(store.events(6,1).isEmpty());
 }

 void legacyLoading(){sql.update("INSERT INTO trips(id,company_id,vehicle_id,status,origin_warehouse_id,trip_code) VALUES(90,1,6,'LOADING',101,'SYNTHETIC-90')");}
 @Test void loadingVerificationDuplicateUsesOneEventAndKeyCannotCrossTrip() {
  legacyLoading();var key=UUID.randomUUID();service.confirmLoadingPresence(101,90,6,key);service.confirmLoadingPresence(101,90,6,key);
  assertEquals(1,store.events(6,1).size());assertTrue(store.event(1,"loading-trip:90:"+key).isPresent());
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status,origin_warehouse_id) VALUES(91,1,2,'LOADING',101)");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,91,2,key));
  assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicle_presence_checkin_requests WHERE request_key=?",Integer.class,key.toString()));
 }
 @Test void loadingVerificationCannotBypassWrongWarehouseBusyOrInTransit() {
  legacyLoading();sql.update("INSERT INTO warehouse_staff_assignments VALUES(7,1,102,true)");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(102,90,6,UUID.randomUUID()));
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status) VALUES(91,1,6,'LOADING')");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,6,UUID.randomUUID()));
  sql.update("DELETE FROM trips WHERE id=91");sql.update("UPDATE trips SET vehicle_id=3 WHERE id=90");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,3,UUID.randomUUID()));
  assertEquals(102L,store.presence(3,1).warehouseId());
  tx.executeWithoutResult(t->service.departure(departing(),8));sql.update("UPDATE trips SET vehicle_id=1 WHERE id=90");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,1,UUID.randomUUID()));assertEquals("IN_TRANSIT",store.presence(1,1).status());
 }
 @Test void loadingVerificationChecksRoleAssignmentTenantAndCurrentTripState()throws Exception {
  legacyLoading();String path="/api/warehouse/vehicle-presence/101/loading-trips/90/confirm-presence";
  http.perform(post(path).contentType("application/json").content("{\"vehicleId\":6}").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isUnauthorized());
  for(String role:List.of("DRIVER","DISPATCHER","MANAGER"))http.perform(post(path).contentType("application/json").content("{\"vehicleId\":6}").header("Authorization","Bearer "+TestJwtSupport.userToken("8",role)).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
  for(String subject:List.of("9","10"))http.perform(post(path).contentType("application/json").content("{\"vehicleId\":6}").header("Authorization",worker(subject)).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
  sql.update("UPDATE trips SET company_id=2 WHERE id=90");http.perform(post(path).contentType("application/json").content("{\"vehicleId\":6}").header("Authorization",worker("7")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isNotFound());
  sql.update("UPDATE trips SET company_id=1 WHERE id=90");
  for(String state:List.of("PLANNED","IN_TRANSIT","DELIVERED","CANCELLED")){sql.update("UPDATE trips SET status=? WHERE id=90",state);assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,6,UUID.randomUUID()));}
  assertTrue(store.events(6,1).isEmpty());
 }
 @Test void concurrentLoadingVerificationsAndWrongWarehouseTransferCannotInventTwoArrivals()throws Exception {
  legacyLoading();UUID key=UUID.randomUUID();
  assertEquals(List.of(true,true),race(()->{service.confirmLoadingPresence(101,90,6,key);return true;},()->{service.confirmLoadingPresence(101,90,6,key);return true;}));assertEquals(1,store.events(6,1).size());
  sql.update("INSERT INTO warehouse_staff_assignments VALUES(7,1,102,true)");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.checkIn(102,6,UUID.randomUUID()));assertEquals(101L,store.presence(6,1).warehouseId());
 }
 @Test void loadingVerificationAndReceiptRollbackTogether() {
  legacyLoading();assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{service.confirmLoadingPresence(101,90,6,UUID.randomUUID());throw new IllegalStateException("synthetic failed confirmation");}));
  assertEquals("UNKNOWN",store.presence(6,1).status());assertTrue(store.events(6,1).isEmpty());assertEquals(0,sql.queryForObject("SELECT count(*) FROM vehicle_presence_checkin_requests WHERE vehicle_id=6",Integer.class));
 }
 @Test void catalogOnlyOffersLoadingRecoveryAtAssignedOriginWithoutCompetingTrip()throws Exception {
  legacyLoading();String path="/api/warehouse/vehicle-presence/101/vehicles";
  http.perform(get(path).param("query","TEST-6").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].loading_trip_id").value(90)).andExpect(jsonPath("$.data[0].loading_trip_code").value("SYNTHETIC-90"));
  sql.update("INSERT INTO trips(company_id,vehicle_id,status) VALUES(1,6,'IN_TRANSIT')");
  http.perform(get(path).param("query","TEST-6").header("Authorization",worker("7"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].loading_trip_id").isEmpty());assertTrue(store.events(6,1).isEmpty());
 }

 @Test void revokedOrInactiveStaffCannotConfirmLoadingPresence() {
  legacyLoading();sql.update("UPDATE warehouse_staff_assignments SET active=false WHERE user_id=7");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,6,UUID.randomUUID()));
  sql.update("UPDATE warehouse_staff_assignments SET active=true WHERE user_id=7");sql.update("UPDATE users SET status='INACTIVE' WHERE id=7");
  assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,()->service.confirmLoadingPresence(101,90,6,UUID.randomUUID()));assertTrue(store.events(6,1).isEmpty());
 }
 @Test void concurrentContextVerificationAndTransferKeepTheActualTripWarehouse()throws Exception {
  legacyLoading();sql.update("INSERT INTO warehouse_staff_assignments VALUES(7,1,102,true)");
  assertEquals(List.of(true,false),race(()->{service.confirmLoadingPresence(101,90,6,UUID.randomUUID());return true;},()->{service.checkIn(102,6,UUID.randomUUID());return true;}));
  assertEquals(101L,store.presence(6,1).warehouseId());assertEquals(1,store.events(6,1).size());
 }

 @Test void physicalConfirmationMustMatchTheVehicleTheWorkerActuallySelected()throws Exception {
  sql.update("INSERT INTO vehicles VALUES(7,1,'Changed truck','TEST-7','AVAILABLE')");
  sql.update("INSERT INTO trips(id,company_id,vehicle_id,status,origin_warehouse_id) VALUES(90,1,7,'LOADING',101)");
  http.perform(post("/api/warehouse/vehicle-presence/101/loading-trips/90/confirm-presence").contentType("application/json").content("{\"vehicleId\":6}")
   .header("Authorization",worker("7")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isConflict());
  assertEquals("UNKNOWN",store.presence(7,1).status());assertTrue(store.events(7,1).isEmpty());
 }
}
