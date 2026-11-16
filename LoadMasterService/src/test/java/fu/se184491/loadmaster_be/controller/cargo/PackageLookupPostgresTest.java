package fu.se184491.loadmaster_be.controller.cargo;

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
class PackageLookupPostgresTest {
 static PostgreSQLContainer pg;
 static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql;
 static MockMvc http;

 @Configuration @EnableWebMvc @EnableTransactionManagement
 @org.springframework.data.web.config.EnableSpringDataWebSupport
 @EnableJpaRepositories(basePackageClasses=CargoPackageRepository.class,
  includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=CargoPackageRepository.class),
  excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*\\.CargoPackageRepository$).*$"))
 @Import({PackageController.class,PackageServiceImpl.class,TestJwtSupport.class,FlaggedUserEnforcementFilter.class})
 static class Config {
  @Bean com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper(){ return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(); }
  @Bean tools.jackson.databind.json.JsonMapper jsonMapper(){return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();}
  @Bean DataSource dataSource(){
   var uri=java.net.URI.create(pg.getJdbcUrl().substring(5));
   if(!"/package_lookup_test".equals(uri.getPath()) || uri.getPort()==5432 ||
    uri.getPort()!=pg.getMappedPort(5432) || !Set.of("localhost","127.0.0.1").contains(uri.getHost()))
    throw new IllegalStateException("Only a fresh local PostgreSQL container is permitted");
   return new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
  }
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds,org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans){
   var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);
   f.setPackagesToScan("fu.se184491.loadmaster_be.entity");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","jakarta.persistence.validation.mode","none",
    "hibernate.resource.beans.container",new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
   return f;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean EntityManager entityManager(EntityManagerFactory f){return SharedEntityManagerCreator.createSharedEntityManager(f);}
  @Bean PackageTypeRepository types(){return mock(PackageTypeRepository.class);}
  @Bean OrderRepository orders(){return mock(OrderRepository.class);}
  @Bean CompanyRepository companies(){return mock(CompanyRepository.class);}
  @Bean CargoPackagePackingProfileRepository profiles(){return mock(CargoPackagePackingProfileRepository.class);}
  @Bean DeliveryRequirementRepository requirements(){return mock(DeliveryRequirementRepository.class);}
  @Bean AuditLogRepository audit(){return mock(AuditLogRepository.class);}
  @Bean CurrentUserService principal(){
   var p=mock(CurrentUserService.class);
   when(p.getCurrentUser()).thenReturn(User.builder().status(UserStatus.ACTIVE).build());
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
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("package_lookup_test");pg.start();
  ctx=new AnnotationConfigWebApplicationContext();
  ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
  ctx.getEnvironment().getPropertySources().remove("systemProperties");
  ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
  sql=new JdbcTemplate(ctx.getBean(DataSource.class));
  sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture 1','T1','one@example.test','ACTIVE'),(2,'C2','Fixture 2','T2','two@example.test','ACTIVE')");
  insert(1,1,"EXACT CODE");insert(2,1,"UNRELATED");insert(3,2,"ONLY-B");insert(4,1,"123");insert(123,1,"NOT-NUMERIC");insert(5,2,"exact code");insert(6,1,"PERCENT%_CODE");
 }
 static void insert(long id,long company,String code){
  sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,status,handling_class,is_pinned,tracking_barcode) VALUES(?,?,?,?,'PENDING','STANDARD',false,?)",id,company,code,"00000000-0000-4000-8000-"+String.format("%012d",id),"TRACK-"+id);
 }
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 String auth(String subject,String role)throws Exception{return "Bearer "+TestJwtSupport.userToken(subject,role);}
 @ParameterizedTest @ValueSource(strings={"DISPATCHER","WAREHOUSE_WORKER"})
 void exactCodeReadForBothRoles(String role)throws Exception{
  http.perform(get("/api/packages").param("packageCode","  exact code  ").header("Authorization",auth("company-a",role)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(1));
 }
 @ParameterizedTest @ValueSource(strings={"NOT-PRESENT","EXACT","EXACT  CODE","ONLY-B","   ","PERCENT"})
 void notFoundPartialSpacesAndOtherTenant(String code)throws Exception{
  http.perform(get("/api/packages").param("packageCode",code).header("Authorization",auth("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.data").isEmpty());
 }
 @Test void numericIsBusinessCodeNotId()throws Exception{
  http.perform(get("/api/packages").param("packageCode","123").header("Authorization",auth("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(4)).andExpect(jsonPath("$.totalElements").value(1));
 }
 @Test void callerCannotOverrideCompany()throws Exception{
  http.perform(get("/api/packages").param("packageCode","EXACT CODE").param("companyId","1").header("Authorization",auth("company-b","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(5)).andExpect(jsonPath("$.totalElements").value(1));
 }
 @Test void wildcardCharactersAreLiteral()throws Exception{
  http.perform(get("/api/packages").param("packageCode","PERCENT%_CODE").header("Authorization",auth("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(6));
 }
 @Test void listWithoutCodeKeepsCompanyScopeAndPagination()throws Exception{
  http.perform(get("/api/packages").param("size","2").param("sort","id,asc").header("Authorization",auth("company-a","DISPATCHER")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.data.length()").value(2));
 }
 @Test void anonymousAndWrongRoleRemainBlocked()throws Exception{
  http.perform(get("/api/packages").param("packageCode","EXACT CODE")).andExpect(status().isUnauthorized());
  http.perform(get("/api/packages").header("Authorization",auth("company-a","DRIVER"))).andExpect(status().isForbidden());
 }
 @Test void wrongAudienceRemainsBlocked()throws Exception{
  String token=TestJwtSupport.token("wrong-audience","loadmaster-web","test","company-a",List.of("DISPATCHER"));
  http.perform(get("/api/packages").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
 }
 @Test void readOnlyLookupDoesNotWritePackages()throws Exception{
  var before=sql.queryForList("SELECT id,company_id,package_code,tracking_barcode,status FROM packages ORDER BY id");
  http.perform(get("/api/packages").param("packageCode","EXACT CODE").header("Authorization",auth("company-a","DISPATCHER"))).andExpect(status().isOk());
  assertEquals(before,sql.queryForList("SELECT id,company_id,package_code,tracking_barcode,status FROM packages ORDER BY id"));
  verifyNoInteractions(ctx.getBean(AuditLogRepository.class));
 }
}
