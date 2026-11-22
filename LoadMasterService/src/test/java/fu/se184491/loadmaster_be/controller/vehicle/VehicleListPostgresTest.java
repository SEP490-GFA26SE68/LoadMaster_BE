package fu.se184491.loadmaster_be.controller.vehicle;
import fu.se184491.loadmaster_be.repository.vehicle.*;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleServiceImpl;

import fu.se184491.loadmaster_be.config.security.FlaggedUserEnforcementFilter;
import fu.se184491.loadmaster_be.controller.package_.PackageScanController;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.*;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.service.cargo.Impl.PackageServiceImpl;
import fu.se184491.loadmaster_be.security.TestJwtSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.postgresql.PostgreSQLContainer;
import jakarta.persistence.*;
import javax.sql.DataSource;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real production controller/security/service/specification against a fresh PostgreSQL container.
 * No Boot configuration, DATABASE_URL, Keycloak, shared database or scheduler is loaded. */
class VehicleListPostgresTest {
 static PostgreSQLContainer pg;
 static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql;
 static MockMvc http;

 @Configuration @EnableWebMvc @EnableTransactionManagement
 @org.springframework.data.web.config.EnableSpringDataWebSupport
 @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
  includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={VehicleRepository.class,VehicleTypeRepository.class,TripRepository.class}),
  excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*\\.(VehicleRepository|VehicleTypeRepository|TripRepository)$).*$"))
 @Import({fu.se184491.loadmaster_be.repository.vehicle.VehicleListRepository.class,VehicleController.class,VehicleServiceImpl.class,fu.se184491.loadmaster_be.exception.GlobalExceptionHandler.class,TestJwtSupport.class,FlaggedUserEnforcementFilter.class})
 static class Config {
  // Real store uses this same isolated datasource; list tests must not require shared presence services.
  @Bean JdbcTemplate presenceJdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore presenceStore(JdbcTemplate sql){return new fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore(sql);}

  @Bean com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper(){ return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(); }
  @Bean fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository loadPlans(){return mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class); }
  @Bean static org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor exceptionTranslation(){ return new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor(); }
  @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
  @Bean DataSource dataSource(){
   var uri=java.net.URI.create(pg.getJdbcUrl().substring(5));
   if(!"/fleet_list_test".equals(uri.getPath()) || uri.getPort()==5432 ||
    uri.getPort()!=pg.getMappedPort(5432) || !Set.of("localhost","127.0.0.1").contains(uri.getHost()))
    throw new IllegalStateException("Only a fresh local PostgreSQL container is permitted");
   return new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
  }
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds,org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans){
   var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);
   f.setPackagesToScan("fu.se184491.loadmaster_be.entity");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","jakarta.persistence.validation.mode","none",
    "hibernate.generate_statistics","true", "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
   return f;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean EntityManager entityManager(EntityManagerFactory f){return SharedEntityManagerCreator.createSharedEntityManager(f);}
  @Bean OrderRepository orders(){return mock(OrderRepository.class);}
  @Bean CompanyRepository companies(){return mock(CompanyRepository.class);}
  @Bean CargoPackagePackingProfileRepository profiles(){return mock(CargoPackagePackingProfileRepository.class);}
  @Bean DeliveryRequirementRepository requirements(){return mock(DeliveryRequirementRepository.class);}
  @Bean AuditLogRepository audit(){return mock(AuditLogRepository.class);}
  @Bean CurrentUserService principal(){
   var p=mock(CurrentUserService.class);
   when(p.getCurrentUser()).thenAnswer(a->{
    var token=(JwtAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
    if (token.getToken().getSubject().equals("no-company")) return User.builder().status(UserStatus.ACTIVE).build();
    var company=new fu.se184491.loadmaster_be.entity.company.Company();
    company.setId(token.getToken().getSubject().equals("company-b")?2L:1L);
    return User.builder().company(company).status(UserStatus.ACTIVE).build();
   });
   when(p.getCurrentCompanyId()).thenAnswer(a->{
    var token=(JwtAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
    return token.getToken().getSubject().equals("company-b")?2L:1L;
   });
   return p;
  }
  @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr(){
   var q=mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);
   when(q.encrypt(any())).thenAnswer(a->a.getArgument(0));when(q.decrypt(any())).thenAnswer(a->a.getArgument(0));return q;
  }
 }
 @BeforeAll static void start(){
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("fleet_list_test");pg.start();
  ctx=new AnnotationConfigWebApplicationContext();
  ctx.getEnvironment().getPropertySources().remove("systemEnvironment");ctx.getEnvironment().getPropertySources().remove("systemProperties");
  ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();sql=new JdbcTemplate(ctx.getBean(DataSource.class));
  sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture A','T1','one@example.test','ACTIVE'),(2,'C2','Fixture B','T2','two@example.test','ACTIVE')");
  sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(8,1,'fixture','fixture@example.test','Fixture','DISPATCHER','ACTIVE','2026-11-12 00:00:00','2026-11-12 00:00:00')");
  for(int i=1;i<=3;i++)sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(?,?,?,400,200,200,2000,0.15,false)",i,i==3?2:1,i==2?"Cold":"Box");
  for(int i=1;i<=100;i++)sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(?,1,?,?,?,?)",i,i%2==0?2:1,i==90?"Target truck":i==10||i==11?"Same name":"Vehicle "+String.format("%03d",i),"PLATE-"+String.format("%03d",i),i==76||i==77?"MAINTENANCE":i==78?"RETIRED":i==4?"IN_TRIP":"AVAILABLE");
  sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(101,2,3,'Foreign truck','FOREIGN','MAINTENANCE')");
  trip(1,1,"LOADING");trip(2,2,"IN_TRANSIT");trip(3,1,"IN_TRANSIT");trip(4,76,"LOADING");trip(5,78,"IN_TRANSIT");
  sql.queryForObject("SELECT setval(pg_get_serial_sequence('vehicles','id'),101,true)",Long.class);
  trip(6,5,"DRAFT");trip(7,6,"PLANNED");trip(8,7,"DELIVERED");trip(9,8,"CANCELLED");
 }
 static void trip(int id,int vehicle,String state){sql.update("INSERT INTO trips(id,company_id,created_by_dispatcher_id,vehicle_id,status) VALUES(?,1,8,?,?)",id,vehicle,state);}
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 static String auth(String subject,String role)throws Exception{return "Bearer "+TestJwtSupport.userToken(subject,role);}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list()throws Exception{return get("/api/vehicles").header("Authorization",auth("company-a","DISPATCHER"));}
 @Test void currentTripMetadataUsesTheSameTripAndPreservesMaintenancePrecedence()throws Exception{
  sql.update("UPDATE trips SET trip_code='TRIP-ONE' WHERE id=1");
  sql.update("UPDATE vehicles SET maintenance_note='Scheduled', maintenance_since=TIMESTAMP '2026-11-13 08:00:00' WHERE id=76");
  http.perform(list().param("search","PLATE-001")).andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].currentTripId").value(1))
    .andExpect(jsonPath("$.data[0].currentTripCode").value("TRIP-ONE"))
    .andExpect(jsonPath("$.data[0].currentTripStatus").value("LOADING"));
  http.perform(list().param("search","PLATE-076")).andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].status").value("MAINTENANCE"))
    .andExpect(jsonPath("$.data[0].maintenanceNote").value("Scheduled"))
    .andExpect(jsonPath("$.data[0].currentTripId").doesNotExist());
 }
 @Test void maintenanceBeyondFirstPageHasFilteredTotal()throws Exception{
  http.perform(list().param("status","MAINTENANCE").param("size","25")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.data[0].id").value(76));
 }
 @ParameterizedTest @ValueSource(strings={"AVAILABLE","IN_TRIP","MAINTENANCE","RETIRED"})
 void derivedStatusCounts(String status)throws Exception{
  int expected=switch(status){case "AVAILABLE"->95;case "IN_TRIP","MAINTENANCE"->2;default->1;};
  http.perform(list().param("status",status)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected)).andExpect(jsonPath("$.data[0].status").value(status));
 }
 @Test void combinedFilters()throws Exception{
  http.perform(list().param("search","PLATE-07").param("status","MAINTENANCE").param("vehicleTypeId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(76));
 }
 @ParameterizedTest @ValueSource(strings={"Target truck","PLATE-090","90"})
 void searchNamePlateAndAdvertisedId(String term)throws Exception{
  http.perform(list().param("search",term)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(90));
 }
 @Test void typeAndPaging()throws Exception{
  http.perform(list().param("vehicleTypeId","2").param("sortBy","id").param("sortDir","DESC").param("page","1").param("size","25")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(50)).andExpect(jsonPath("$.data[0].id").value(50));
 }
 @Test void stableTextNumericDerivedOrdering()throws Exception{
  http.perform(list().param("sortBy","name").param("sortDir","ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(10)).andExpect(jsonPath("$.data[1].id").value(11));
  http.perform(list().param("sortBy","name").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(100));
  http.perform(list().param("sortBy","vehicleTypeId").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(2));
  http.perform(list().param("sortBy","status").param("sortDir","ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(3));
  http.perform(list().param("sortBy","status").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(78));
  http.perform(list().param("sortBy","vehicleTypeName").param("sortDir","ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
  http.perform(list().param("sortBy","vehicleTypeName").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(2));
  http.perform(list().param("sortBy","companyName").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
  http.perform(list().param("sortBy","licensePlate").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(100));
 }
 @ParameterizedTest @ValueSource(strings={"BUSY","IN_USE","UNKNOWN"})
 void nonDomainStatusesRejected(String value)throws Exception{http.perform(list().param("status",value)).andExpect(status().isBadRequest());}
 @Test void arbitraryPathsRejected()throws Exception{
  http.perform(list().param("sortBy","vehicleType.company.id")).andExpect(status().isBadRequest());
  http.perform(list().param("sortBy","createdAt")).andExpect(status().isBadRequest());
  http.perform(list().param("sortDir","WRONG")).andExpect(status().isBadRequest());
 }
 @Test void tenantScopeAndEmpty()throws Exception{
  http.perform(list().param("search","Foreign").param("companyId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  http.perform(get("/api/vehicles").header("Authorization",auth("company-b","DISPATCHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(101));
 }
 @Test void existingDetailResolverAgreesWithList()throws Exception{
  for(int id:List.of(1,2,4,5,6,7,8,76,78)){
   var detail=http.perform(get("/api/vehicles/"+id).header("Authorization",auth("company-a","DISPATCHER"))).andExpect(status().isOk()).andReturn();
   var mapper=ctx.getBean(tools.jackson.databind.json.JsonMapper.class);
   String expected=mapper.readTree(detail.getResponse().getContentAsString()).get("data").get("status").asString();
   http.perform(list().param("search","PLATE-"+String.format("%03d",id))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value(expected));
  }
 }
 @Test void listDoesNotWriteVehiclesOrTrips()throws Exception{
  var before=sql.queryForList("SELECT id,status,vehicle_id FROM trips ORDER BY id");var vehicles=sql.queryForList("SELECT id,status FROM vehicles ORDER BY id");
  http.perform(list().param("status","AVAILABLE")).andExpect(status().isOk());
  assertEquals(before,sql.queryForList("SELECT id,status,vehicle_id FROM trips ORDER BY id"));assertEquals(vehicles,sql.queryForList("SELECT id,status FROM vehicles ORDER BY id"));
 }
 @Test void noPerRowOccupancyQueries()throws Exception{
  var stats=ctx.getBean(EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.clear();
  http.perform(list().param("size","50")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(50));
  assertTrue(stats.getPrepareStatementCount()<=2,"List must use page + count only; actual="+stats.getPrepareStatementCount());
 }
 @Test void securityUnchanged()throws Exception{
  http.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
  http.perform(get("/api/vehicles").header("Authorization",auth("company-a","MANAGER"))).andExpect(status().isOk());
  http.perform(post("/api/vehicles").contentType("application/json").content("{\"name\":\"Denied fixture\",\"licensePlate\":\"TEST-DENIED\",\"vehicleTypeId\":1}")
    .header("Authorization",auth("company-a","MANAGER"))).andExpect(status().isForbidden());
  http.perform(get("/api/vehicles").header("Authorization",auth("company-a","DRIVER"))).andExpect(status().isForbidden());
 }
 @Test void existingCreateFlowStillWorksInContainerOnly()throws Exception{
  try {
   http.perform(post("/api/vehicles").contentType("application/json")
    .content("{\"name\":\"New fixture\",\"licensePlate\":\"TEST-CREATE\",\"vehicleTypeId\":1}")
    .header("Authorization",auth("company-a","DISPATCHER"))).andExpect(status().isCreated())
    .andExpect(jsonPath("$.data.companyId").value(1)).andExpect(jsonPath("$.data.status").value("AVAILABLE"));
   assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicles WHERE license_plate='TEST-CREATE' AND company_id=1",Integer.class));
  } finally { sql.update("DELETE FROM vehicles WHERE license_plate='TEST-CREATE'"); }
 }
 @Test void missingCompanyPreservesExistingAdmissionError()throws Exception{
  http.perform(get("/api/vehicles").header("Authorization",auth("no-company","DISPATCHER")))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMPANY_REQUIRED"));
 }
}
