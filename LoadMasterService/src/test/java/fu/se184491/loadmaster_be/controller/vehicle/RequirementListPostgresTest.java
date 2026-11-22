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
class RequirementListPostgresTest {
 static PostgreSQLContainer pg;
 static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql;
 static MockMvc http;

 @Configuration @EnableWebMvc @EnableTransactionManagement
 @org.springframework.data.web.config.EnableSpringDataWebSupport
 @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
  includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={VehicleRepository.class,VehicleTypeRepository.class,TripRepository.class,DeliveryRequirementRepository.class,CargoPackageRepository.class,fu.se184491.loadmaster_be.repository.customer.CustomerRepository.class,fu.se184491.loadmaster_be.repository.account.UserRepository.class}),
  excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*\\.(VehicleRepository|VehicleTypeRepository|TripRepository|DeliveryRequirementRepository|CargoPackageRepository|CustomerRepository|UserRepository)$).*$"))
 @Import({fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility.class,fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore.class,fu.se184491.loadmaster_be.controller.trip.TripController.class,fu.se184491.loadmaster_be.service.trip.impl.TripServiceImpl.class,fu.se184491.loadmaster_be.controller.planning.DeliveryRequirementController.class,fu.se184491.loadmaster_be.service.planning.Impl.DeliveryRequirementServiceImpl.class,fu.se184491.loadmaster_be.repository.vehicle.VehicleListRepository.class,VehicleController.class,VehicleServiceImpl.class,fu.se184491.loadmaster_be.exception.GlobalExceptionHandler.class,TestJwtSupport.class,FlaggedUserEnforcementFilter.class})
 static class Config {
  @Bean fu.se184491.loadmaster_be.service.planning.RequirementPackageResolver packageResolver(fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository packages, fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository requirements){ return new fu.se184491.loadmaster_be.service.planning.RequirementPackageResolver(packages,requirements); }
  @Bean com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper(){ return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(); }
  @Bean static org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor exceptionTranslation(){ return new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor(); }
  @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
  @Bean JdbcTemplate presenceJdbc(DataSource ds){return new JdbcTemplate(ds);}
  @Bean DataSource dataSource(){
   var uri=java.net.URI.create(pg.getJdbcUrl().substring(5));
   if(!"/requirement_list_test".equals(uri.getPath()) || uri.getPort()==5432 ||
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
  @Bean fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository stops(){return mock(fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository.class);}

  @Bean AuditLogRepository audit(){return mock(AuditLogRepository.class);}
  @Bean fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository warehouses(){return mock(fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository.class);}
  @Bean fu.se184491.loadmaster_be.repository.route.RoutePlanRepository routes(){return mock(fu.se184491.loadmaster_be.repository.route.RoutePlanRepository.class);}
  @Bean fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository plans(){return mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class);}
  @Bean fu.se184491.loadmaster_be.service.trip.TripStatusService transitions(){return mock(fu.se184491.loadmaster_be.service.trip.TripStatusService.class);}
  @Bean fu.se184491.loadmaster_be.service.trip.TripLifecycleService lifecycle(){return mock(fu.se184491.loadmaster_be.service.trip.TripLifecycleService.class);}
  @Bean fu.se184491.loadmaster_be.service.trip.TripVehicleChoiceService choices(){return mock(fu.se184491.loadmaster_be.service.trip.TripVehicleChoiceService.class);}
  @Bean CurrentUserService principal(){
   var p=mock(CurrentUserService.class);
   when(p.getCurrentUser()).thenAnswer(a->{
    var token=(JwtAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
    if (token.getToken().getSubject().equals("no-company")) return User.builder().status(UserStatus.ACTIVE).build();
    var company=new fu.se184491.loadmaster_be.entity.company.Company();
    company.setId(token.getToken().getSubject().equals("company-b")?2L:1L);
    return User.builder().id(8L).company(company).status(UserStatus.ACTIVE).build();
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
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("requirement_list_test");pg.start();
  ctx=new AnnotationConfigWebApplicationContext();
  ctx.getEnvironment().getPropertySources().remove("systemEnvironment");ctx.getEnvironment().getPropertySources().remove("systemProperties");
  ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();sql=new JdbcTemplate(ctx.getBean(DataSource.class));
  sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture A','T1','one@example.test','ACTIVE'),(2,'C2','Fixture B','T2','two@example.test','ACTIVE')");
  sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(8,1,'fixture','fixture@example.test','Fixture','DISPATCHER','ACTIVE','2026-11-12 00:00:00','2026-11-12 00:00:00')");
  sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Box',400,200,200,2000,0.15,false)");
  sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES(1,1,1,'Fixture','TEST','AVAILABLE')");
  sql.update("INSERT INTO trips(id,company_id,created_by_dispatcher_id,vehicle_id,status,trip_code) VALUES(9,1,8,1,'DRAFT','TRIP-TARGET')");
  sql.update("INSERT INTO customers(id,company_id,name,contact_phone,address) VALUES(1,1,'Khách Đà Nẵng','000','Fixture'),(2,2,'Foreign','000','Fixture')");
  for(int i=20;i<=23;i++)sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'2026-11-12 00:00:00','2026-11-12 00:00:00')",i,i==21?2:1,"driver-"+i,"driver"+i+"@example.test","Driver "+i,i==23?"DISPATCHER":"DRIVER",i==22?"INACTIVE":"ACTIVE");
  for(int i=1;i<=110;i++)sql.update("INSERT INTO delivery_requirements(id,company_id,customer_id,destination,deadline,priority,status,created_at,trip_id,expected_package_count) VALUES(?,1,1,?,'2026-11-12T23:59:59.999999',? ,?,localtimestamp,?,4)",i,i>=109?"Target destination":"Depot "+i,i>=109?"HIGH":"NORMAL",i>=109?"ASSIGNED":"PENDING",i>=109?9:null);
  sql.update("INSERT INTO delivery_requirements(id,company_id,customer_id,destination,deadline,priority,status,created_at) VALUES(111,2,2,'Foreign','2026-11-12T08:00:00','HIGH','PENDING',localtimestamp)");
  sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status,dropped_requirement_id) VALUES(1,1,'FIX-1','one','STANDARD',false,'PENDING',109),(2,1,'FIX-2','two','STANDARD',false,'RETURNED',null)");
  sql.update("INSERT INTO delivery_requirement_packages(delivery_requirement_id,package_id) VALUES(110,2)");
 }
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(String params)throws Exception{
  return get("/api/delivery-requirements?"+params).header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DISPATCHER"));
 }
 @Test void tripCodeSearchUsesRealTripNotUnrelatedId()throws Exception{
  http.perform(req("search=TRIP-TARGET&size=25")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.data[0].tripCode").value("TRIP-TARGET"));
 }
 @Test void partialDerivedBeforePageAndCounts()throws Exception{
  http.perform(req("status=PARTIAL&priority=HIGH&search=Target&size=1&sortBy=id&sortDir=ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.data[0].id").value(109)).andExpect(jsonPath("$.data[0].shortDelivered").value(true));
 }
 @Test void assignedExcludesDerivedPartial()throws Exception{
  http.perform(req("status=ASSIGNED")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }
 @ParameterizedTest @ValueSource(strings={"109","Target","TRIP-TARGET","khach da nang","  KHACH   NANG  ","\u00a0KHACH\u00a0\u00a0NANG\u00a0"})
 void advertisedSearchFieldsAndFolding(String value)throws Exception{
  http.perform(req("size=25").param("search",value)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(value.equals("109")?1:value.contains("khach")||value.contains("KHACH")?110:2));
 }
 @Test void pagingGlobalSortAndStableTie()throws Exception{
  http.perform(req("sortBy=id&sortDir=DESC&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(110));
  http.perform(req("sortBy=deadline&sortDir=ASC&page=4&size=25")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(101)).andExpect(jsonPath("$.totalElements").value(110));
 }
 @Test void localDateInclusiveAndExclusiveBoundaries()throws Exception{
  http.perform(req("deadlineFrom=2026-11-12T00:00:00&deadlineTo=2026-11-13T00:00:00&deadlineToExclusive=true")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(110));
  http.perform(req("deadlineFrom=2026-11-13T00:00:00")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }
 @ParameterizedTest @ValueSource(strings={"sortBy=company.secret","sortDir=sideways","status=BOGUS","priority=BOGUS","deadlineFrom=2026-11-13T00:00:00&deadlineTo=2026-11-12T00:00:00"})
 void invalidContractIs400(String params)throws Exception{http.perform(req(params)).andExpect(status().isBadRequest());}
 @Test void companyCannotSpoofScope()throws Exception{
  http.perform(req("search=Foreign&companyId=2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  http.perform(get("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-b","DISPATCHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
 }
 @Test void absentOrWrongRoleCannotRead()throws Exception{
  http.perform(get("/api/delivery-requirements")).andExpect(status().isUnauthorized());
  http.perform(get("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DRIVER"))).andExpect(status().isForbidden());
 }
 @ParameterizedTest @ValueSource(strings={"id","destination","deadline","priority","packages","status","trip","createdAt"})
 void allWhitelistedSortsRunGlobally(String field)throws Exception{
  http.perform(req("sortBy="+field+"&sortDir=DESC&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(110)).andExpect(jsonPath("$.data.length()").value(1));
 }
 @Test void driverOptionsUseRealTenantRoleAndActiveRepositoryFilter()throws Exception{
  http.perform(get("/api/trips/driver-options").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(20));
  http.perform(get("/api/trips/driver-options").header("Authorization","Bearer "+TestJwtSupport.userToken("company-b","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(21));
  http.perform(get("/api/trips/driver-options").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","MANAGER"))).andExpect(status().isForbidden());
 }
 @Test void tripSearchFiltersTotalsAndTenantRemainServerSide()throws Exception{
  http.perform(get("/api/trips?search=TRIP-TARGET&status=DRAFT&vehicleId=1&unassignedDriver=true&size=1&sortBy=tripCode&sortDir=asc")
   .header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(9));
  http.perform(get("/api/trips?search=TRIP-TARGET").header("Authorization","Bearer "+TestJwtSupport.userToken("company-b","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }

 // DISPATCHER and MANAGER both hold DELIVERY_DEMAND_MANAGE; WAREHOUSE_WORKER only reads.
 @Test void createStillUsesDedicatedPermissionAndTenantGuard()throws Exception{
  String body="{\"customerId\":1,\"destination\":\"Fixture create\",\"deadline\":\"2099-11-12T12:00:00\",\"priority\":\"NORMAL\",\"expectedPackageCount\":4,\"packageIds\":[]}";
  sql.queryForObject("SELECT setval(pg_get_serial_sequence('delivery_requirements','id'),111,true)",Long.class);
  try {
   http.perform(post("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DISPATCHER")).contentType("application/json").content(body))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.companyId").value(1)).andExpect(jsonPath("$.data.receivedPackageCount").value(0));
   http.perform(post("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","MANAGER")).contentType("application/json").content(body))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.companyId").value(1));
   http.perform(post("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","WAREHOUSE_WORKER")).contentType("application/json").content(body))
    .andExpect(status().isForbidden());
   http.perform(post("/api/delivery-requirements").header("Authorization","Bearer "+TestJwtSupport.userToken("company-a","DISPATCHER")).contentType("application/json").content(body.replace("\"customerId\":1","\"customerId\":2")))
    .andExpect(status().isBadRequest());
  } finally { sql.update("DELETE FROM delivery_requirements WHERE destination='Fixture create'"); }
 }
 @ParameterizedTest @ValueSource(strings={"no-such-customer","%","_"})
 void nonexistentOrLiteralWildcardDoesNotReturnUnrelatedRows(String term)throws Exception{
  http.perform(req("size=25").param("search",term)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }

}
