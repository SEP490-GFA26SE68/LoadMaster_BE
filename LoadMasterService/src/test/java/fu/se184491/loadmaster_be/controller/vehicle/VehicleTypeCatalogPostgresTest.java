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
class VehicleTypeCatalogPostgresTest {
 static PostgreSQLContainer pg;
 static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql;
 static MockMvc http;

 @Configuration @EnableWebMvc @EnableTransactionManagement
 @org.springframework.data.web.config.EnableSpringDataWebSupport
 @EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository",
  includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={VehicleRepository.class,VehicleTypeRepository.class,TripRepository.class}),
  excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*\\.(VehicleRepository|VehicleTypeRepository|TripRepository)$).*$"))
 @Import({VehicleTypeController.class,fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleTypeServiceImpl.class,fu.se184491.loadmaster_be.repository.vehicle.VehicleListRepository.class,VehicleController.class,VehicleServiceImpl.class,fu.se184491.loadmaster_be.exception.GlobalExceptionHandler.class,TestJwtSupport.class,FlaggedUserEnforcementFilter.class})
 static class Config {
  @Bean JdbcTemplate presenceJdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore presenceStore(JdbcTemplate sql){return new fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore(sql);}

  @Bean com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper(){ return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(); }
  @Bean fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository loadPlans(){return mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class); }
  @Bean static org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor exceptionTranslation(){ return new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor(); }
  @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
  @Bean DataSource dataSource(){
   var uri=java.net.URI.create(pg.getJdbcUrl().substring(5));
   if(!"/type_catalog_test".equals(uri.getPath()) || uri.getPort()==5432 ||
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
  @Bean @Primary EntityManager entityManager(EntityManagerFactory f){return SharedEntityManagerCreator.createSharedEntityManager(f);}
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
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("type_catalog_test");pg.start();
  ctx=new AnnotationConfigWebApplicationContext();
  ctx.getEnvironment().getPropertySources().remove("systemEnvironment");ctx.getEnvironment().getPropertySources().remove("systemProperties");
  ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();sql=new JdbcTemplate(ctx.getBean(DataSource.class));
  sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture A','T1','one@example.test','ACTIVE'),(2,'C2','Fixture B','T2','two@example.test','ACTIVE')");
 }
 @BeforeEach void fixture(){types(100);}
 static void types(int count){
  sql.execute("TRUNCATE vehicle_types RESTART IDENTITY CASCADE");
  for(int i=count;i>=1;i--)sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(?,1,?,400,200,200,?,0.15,false)",i,i==10||i==11?"Same type":"Type "+String.format("%03d",i),i*100);
  sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,max_cog_offset_ratio,hazardous_capable) VALUES(1001,2,'Foreign type',400,200,200,9999,0.15,false)");
 }
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(String params,String who,String role)throws Exception{
  return get("/api/vehicle-types?"+params).header("Authorization","Bearer "+TestJwtSupport.userToken(who,role));
 }
 @ParameterizedTest @ValueSource(ints={21,50,100})
 void catalogAllPagesHaveCorrectTotalAndTargetBeyondFirstPage(int count)throws Exception{
  types(count);
  int page=(count-1)/20;
  http.perform(req("size=20&page="+page,"company-a","DISPATCHER")).andExpect(status().isOk())
   .andExpect(jsonPath("$.totalElements").value(count)).andExpect(jsonPath("$.totalPages").value((count+19)/20))
   .andExpect(jsonPath("$.last").value(true));
  http.perform(req("size=20&search=Type "+String.format("%03d",count),"company-a","DISPATCHER"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(count));
 }
 @Test void fixedSortMustHaveStableIdTie(){
  var repo=mock(VehicleTypeRepository.class);
  var principal=mock(CurrentUserService.class);
  when(principal.getCurrentUser()).thenReturn(User.builder().company(fu.se184491.loadmaster_be.entity.company.Company.builder().id(1L).build()).build());
  when(repo.findAllByCompanyId(eq(1L),any())).thenReturn(org.springframework.data.domain.Page.empty());
  var service=new fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleTypeServiceImpl(repo,mock(EntityManager.class),principal,mock(VehicleRepository.class),mock(fu.se184491.loadmaster_be.repository.trip.TripRepository.class),mock(fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository.class));
  service.getVehicleTypes(0,20,null);
  var capture=org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
  verify(repo).findAllByCompanyId(eq(1L),capture.capture());
  assertEquals(List.of(org.springframework.data.domain.Sort.Order.asc("name"),org.springframework.data.domain.Sort.Order.asc("id")),capture.getValue().getSort().toList());
 }
 @Test void equalNameRowsUseStableIdOrderInPostgres()throws Exception{
  http.perform(req("search=Same&size=1&page=0","company-a","DISPATCHER")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(10));
  http.perform(req("search=Same&size=1&page=1","company-a","DISPATCHER")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(11));
 }
 @Test void tenantAndCompanySpoofNeverReturnForeignType()throws Exception{
  http.perform(req("search=Foreign&companyId=2","company-a","DISPATCHER")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  http.perform(req("companyId=1","company-b","DISPATCHER")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(1001));
 }
 @Test void validReadersAndExistingGuardsRemain()throws Exception{
  http.perform(req("search=Type 100","company-a","MANAGER")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  http.perform(req("","company-a","DRIVER")).andExpect(status().isForbidden());
  http.perform(req("","company-a","SYSTEM_ADMIN")).andExpect(status().isForbidden());
  // Anonymous callers are rejected by the authenticated security filter chain.
  http.perform(get("/api/vehicle-types")).andExpect(status().isUnauthorized());
  http.perform(req("","no-company","DISPATCHER")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMPANY_REQUIRED"));
 }
 @Test void invalidAudienceAndBadPageAreRejected()throws Exception{
  http.perform(get("/api/vehicle-types").header("Authorization", "Bearer " + TestJwtSupport.token("wrong-api", "loadmaster-web", "company-a", "company-a", List.of("DISPATCHER")))).andExpect(status().isUnauthorized());
  http.perform(req("page=-1","company-a","DISPATCHER")).andExpect(status().isBadRequest());
  http.perform(req("size=0","company-a","DISPATCHER")).andExpect(status().isBadRequest());
 }
}
