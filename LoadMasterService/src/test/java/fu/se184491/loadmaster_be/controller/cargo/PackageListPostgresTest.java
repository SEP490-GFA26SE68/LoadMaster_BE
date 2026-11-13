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
class PackageListPostgresTest {
 static PostgreSQLContainer pg;
 static AnnotationConfigWebApplicationContext ctx;
 static JdbcTemplate sql;
 static MockMvc http;

 @Configuration @EnableWebMvc @EnableTransactionManagement
 @org.springframework.data.web.config.EnableSpringDataWebSupport
 @EnableJpaRepositories(basePackageClasses=PackageRepository.class,
  includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={PackageRepository.class, PackageTypeRepository.class}),
  excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="^(?!.*\\.(PackageRepository|PackageTypeRepository)$).*$"))
 @Import({PackageController.class,PackageServiceImpl.class,fu.se184491.loadmaster_be.service.cargo.Impl.PackageTypeServiceImpl.class,PackageTypeController.class,fu.se184491.loadmaster_be.exception.GlobalExceptionHandler.class,TestJwtSupport.class,FlaggedUserEnforcementFilter.class})
 static class Config {
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
  @Bean OrderRepository orders(){return mock(OrderRepository.class);}
  @Bean CompanyRepository companies(){return mock(CompanyRepository.class);}
  @Bean CargoPackagePackingProfileRepository profiles(){return mock(CargoPackagePackingProfileRepository.class);}
  @Bean DeliveryRequirementRepository requirements(){return mock(DeliveryRequirementRepository.class);}
  @Bean AuditLogRepository audit(){return mock(AuditLogRepository.class);}
  @Bean CurrentUserService principal(){
   var p=mock(CurrentUserService.class);
   when(p.getCurrentUser()).thenAnswer(a->{
    var token=(JwtAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
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
  pg=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("package_lookup_test");pg.start();
  ctx=new AnnotationConfigWebApplicationContext();
  ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
  ctx.getEnvironment().getPropertySources().remove("systemProperties");
  ctx.setServletContext(new MockServletContext());ctx.register(Config.class);ctx.refresh();
  http=MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
  sql=new JdbcTemplate(ctx.getBean(DataSource.class));
  sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES(1,'C1','Fixture 1','T1','one@example.test','ACTIVE'),(2,'C2','Fixture 2','T2','two@example.test','ACTIVE')");
  for(int i=1;i<=80;i++) sql.update("INSERT INTO package_types(id,company_id,name,type_code,weight_kg) VALUES(?,1,?,?,?)",i,i==61?"Target type":"Same type", "TYPE-"+i,i);
  sql.update("INSERT INTO package_types(id,company_id,name,type_code,weight_kg) VALUES(81,2,'Target type foreign','FOREIGN',1)");
  for(int i=1;i<=200;i++) sql.update("INSERT INTO packages(id,company_id,package_type_id,package_code,package_name,tracking_barcode,qr_token,status,handling_class,is_pinned,actual_weight_kg,destination) VALUES(?,1,?,?,?,?,?,?,?,false,?,?)",
    i,61, "PKG-"+String.format("%03d",i),i==126?"Target parcel":"Ordinary", "TRACK-"+i,
    "00000000-0000-4000-8000-"+String.format("%012d",i),i%2==0?"RETURNED":"PENDING",i%3==0?"FRAGILE":"STANDARD",i,"Depot");
  sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,status,handling_class,is_pinned) VALUES(201,2,'FOREIGN-TARGET','00000000-0000-4000-8000-000000000201','RETURNED','STANDARD',false)");
  sql.update("UPDATE packages SET actual_weight_kg=null WHERE id=200");
  sql.update("UPDATE packages SET destination='Bi\u00ean Ho\u00e0' WHERE id=126");
  sql.update("UPDATE package_types SET name='Same type Bi\u00ean Ho\u00e0' WHERE id=80");
 }
 @AfterAll static void stop(){if(ctx!=null)ctx.close();if(pg!=null)pg.stop();}
 String auth(String subject,String role)throws Exception{return "Bearer "+TestJwtSupport.userToken(subject,role);}

 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder packages()throws Exception{
  return get("/api/packages").header("Authorization",auth("company-a","WAREHOUSE_WORKER"));
 }
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder types()throws Exception{
  return get("/api/package-types").header("Authorization",auth("company-a","WAREHOUSE_WORKER"));
 }
 @Test void searchBeyondPageFiveWithFilteredTotal()throws Exception{
  http.perform(packages().param("search","PKG-126").param("size","25"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(126));
 }
 @Test void combinedFiltersAndNumericSortBeforePaging()throws Exception{
  http.perform(packages().param("status","RETURNED").param("handlingClass","FRAGILE").param("packageTypeId","61").param("sortBy","actualWeightKg").param("sortDir","DESC").param("page","1").param("size","25"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(33)).andExpect(jsonPath("$.data[0].id").value(48));
 }
 @Test void returnedIsGlobal()throws Exception{
  http.perform(packages().param("status","RETURNED").param("size","25"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(100));
 }
 @ParameterizedTest @ValueSource(strings={"Target parcel","TRACK-126","TYPE-61","Target type","126"})
 void supportedSearchFields(String search)throws Exception{
  http.perform(packages().param("search",search)).andExpect(status().isOk())
   .andExpect(jsonPath("$.totalElements").value(search.equals("TYPE-61")||search.equals("Target type")?200:1));
 }
 @Test void searchWithFilterAndPages()throws Exception{
  http.perform(packages().param("search","PKG-1").param("status","RETURNED").param("sortBy","packageCode").param("sortDir","DESC").param("page","1").param("size","25"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(50)).andExpect(jsonPath("$.data[0].id").value(148));
 }
 @Test void emptyAndOtherCompanyDoNotLeak()throws Exception{
  http.perform(packages().param("search","FOREIGN-TARGET").param("companyId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }
 @Test void exactLookupRemainsExact()throws Exception{
  http.perform(packages().param("packageCode","PKG-12")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }
 @ParameterizedTest @ValueSource(strings={"status","handlingClass","sortBy","sortDir"})
 void invalidQueryRejected(String field)throws Exception{
  http.perform(packages().param(field,"NOT_VALID")).andExpect(status().isBadRequest());
 }
 @Test void legacySortValidated()throws Exception{
  http.perform(packages().param("sort","company.id,asc")).andExpect(status().isBadRequest());
 }
 @Test void numericAscDescNullLastAndStableTie()throws Exception{
  http.perform(packages().param("sortBy","actualWeightKg").param("sortDir","ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
  http.perform(packages().param("sortBy","actualWeightKg").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(199));
  http.perform(packages().param("sortBy","actualWeightKg").param("sortDir","DESC").param("page","7").param("size","25")).andExpect(status().isOk()).andExpect(jsonPath("$.data[24].id").value(200));
  http.perform(packages().param("sortBy","destination").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
 }
 @Test void currentPageIncludesTypeDisplayWithoutCatalogFetch()throws Exception{
  http.perform(packages().param("search","PKG-126")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].packageTypeName").value("Target type")).andExpect(jsonPath("$.data[0].packageTypeCode").value("TYPE-61"));
 }
 @Test void literalWildcardsDoNotBroadenSearch()throws Exception{
  http.perform(packages().param("search","%_")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
 }
 @ParameterizedTest @ValueSource(strings={"Target type","TYPE-61","61"})
 void typeSearchBeyondPageTwo(String search)throws Exception{
  http.perform(types().param("search",search)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(61));
 }
 @Test void typeSortAndTieBreak()throws Exception{
  http.perform(types().param("sortBy","name").param("sortDir","ASC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
  http.perform(types().param("sortBy","name").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(61));
  http.perform(types().param("sortBy","weightKg").param("sortDir","DESC")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(80));
 }
 @Test void typeTenantAndInvalidSort()throws Exception{
  http.perform(types().param("search","FOREIGN").param("companyId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  http.perform(types().param("sortBy","company.id")).andExpect(status().isBadRequest());
 }
 @ParameterizedTest @ValueSource(strings={"bien hoa", "126 bien", "  Bi\u00ean   Hoa 126  "})
 void accentInsensitiveTermsAcrossFields(String search)throws Exception{
  http.perform(packages().param("search",search)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(126));
 }
 @Test void typeAccentInsensitiveSearch()throws Exception{
  http.perform(types().param("search","hoa bien")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(80));
 }
 @Test void datesAreNotInventedSortFields()throws Exception{
  http.perform(packages().param("sortBy","createdAt")).andExpect(status().isBadRequest());
  http.perform(types().param("sortBy","createdAt")).andExpect(status().isBadRequest());
 }
 @Test void packagesWithoutTypeRemainSearchable()throws Exception{
  sql.update("UPDATE packages SET package_type_id=null WHERE id=200");
  try {
   http.perform(packages().param("search","PKG-200")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.data[0].id").value(200));
  } finally { sql.update("UPDATE packages SET package_type_id=61 WHERE id=200"); }
 }
}
