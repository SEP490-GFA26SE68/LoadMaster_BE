package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.optimization.LoadingOptimizationController;
import fu.se184491.loadmaster_be.controller.warehouse.WarehouseController;
import fu.se184491.loadmaster_be.controller.driver.DriverController;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.optimization.*;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.optimization.*;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({LoadingOptimizationController.class,WarehouseController.class,DriverController.class})
@Import({TestJwtSupport.class,OptimizationAccess.class,CurrentUserService.class})
class OptimizationReadSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean LoadingOptimizationService loading;
 @MockitoBean LoadPlanQueryService plans;
 @MockitoBean fu.se184491.loadmaster_be.service.driver.DriverDeliveryService driverDelivery;
 @MockitoBean OptimizationSubmissionService submission;
 @MockitoBean OptimizationClient client;
 @MockitoBean UserRepository users;
 @MockitoBean OptimizationJobRepository jobs;
 @MockitoBean TripRepository trips;
 @MockitoBean PackagePlacementRepository placements;
 @MockitoBean WarehouseService warehouse;
 @MockitoBean DriverService driver;
 @MockitoBean GpsTrackingService gps;
 @MockitoBean TripExceptionService exceptions;
 @MockitoBean TripLookupHelper lookup;
 @BeforeEach void seed(){
  var a=Company.builder().id(1L).build();var b=Company.builder().id(2L).build();
  var ta=Trip.builder().id(7L).company(a).build();var tb=Trip.builder().id(8L).company(b).build();
  var ja=OptimizationJob.builder().id(1L).jobUuid("own-job").trip(ta).build();
  var jb=OptimizationJob.builder().id(2L).jobUuid("other-job").trip(tb).build();
  var pa=LoadPlan.builder().id(47L).job(ja).build();var pb=LoadPlan.builder().id(48L).job(jb).build();
  when(users.findByKeycloakId("own")).thenReturn(Optional.of(User.builder().id(11L).company(a).build()));
  when(users.findByKeycloakId("other")).thenReturn(Optional.of(User.builder().id(22L).company(b).build()));
  when(users.findById(11L)).thenReturn(Optional.of(User.builder().id(11L).company(a).build()));
  when(users.findById(22L)).thenReturn(Optional.of(User.builder().id(22L).company(b).build()));
  when(jobs.findByJobUuid("own-job")).thenReturn(Optional.of(ja));when(jobs.findByJobUuid("other-job")).thenReturn(Optional.of(jb));
  when(jobs.findByJobUuid("orphan")).thenReturn(Optional.of(OptimizationJob.builder().jobUuid("orphan").build()));
  when(trips.findById(7L)).thenReturn(Optional.of(ta));when(trips.findById(8L)).thenReturn(Optional.of(tb));
  when(placements.findById(30L)).thenReturn(Optional.of(PackagePlacement.builder().id(30L).loadPlan(pa).build()));
  when(placements.findById(31L)).thenReturn(Optional.of(PackagePlacement.builder().id(31L).loadPlan(pb).build()));
  when(client.getJobStatus("own-job")).thenReturn("{\"status\":\"COMPLETED\"}");
  when(client.getJobPlans("own-job")).thenReturn("{\"plans\":[]}");
  when(warehouse.getTasks(1L,null,false)).thenReturn(List.of());when(driver.listTrips(11L)).thenReturn(List.of());
 }
 String user(String role)throws Exception{return "Bearer "+TestJwtSupport.userToken("own",role);}
 @ParameterizedTest @ValueSource(strings={"","/plans"}) void ownerCanReadJobAndPlans(String suffix)throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/own-job"+suffix).header("Authorization",user("DISPATCHER"))).andExpect(status().isOk());
 }
 @ParameterizedTest @ValueSource(strings={"","/plans"}) void foreignJobRejectedBeforeRemoteCall(String suffix)throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/other-job"+suffix).header("Authorization",user("DISPATCHER"))).andExpect(status().isForbidden());verifyNoInteractions(client);
 }
 @ParameterizedTest @ValueSource(strings={"missing","1","orphan"}) void missingNumericOrOrphanJobNeverProxied(String key)throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/"+key).header("Authorization",user("DISPATCHER"))).andExpect(status().is(key.equals("orphan")?403:404));verifyNoInteractions(client);
 }
 @Test void spoofedCompanyQueryDoesNotAuthorizeJob()throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/other-job").param("companyId","2").header("Authorization",user("DISPATCHER"))).andExpect(status().isForbidden());verifyNoInteractions(client);
 }
 @Test void anonymousReadIs401()throws Exception{mvc.perform(get("/api/trips/optimization-jobs/own-job")).andExpect(status().isUnauthorized());verifyNoInteractions(client);}
 @Test void wrongAudienceIs401()throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/own-job").header("Authorization","Bearer "+TestJwtSupport.token("wrong","loadmaster-web","own","own",List.of("DISPATCHER")))).andExpect(status().isUnauthorized());verifyNoInteractions(client);
 }
 @Test void creditServiceRoleIsNotAUserReadBypass()throws Exception{
  mvc.perform(get("/api/trips/optimization-jobs/own-job").header("Authorization","Bearer "+TestJwtSupport.serviceToken("loadmaster-api","optimization-service","service-account-optimization-service",List.of("OPTIMIZATION_CREDIT")))).andExpect(status().isForbidden());verifyNoInteractions(client);
 }
 @Test void driverCannotUseDispatcherProxy()throws Exception{mvc.perform(get("/api/trips/optimization-jobs/own-job").header("Authorization",user("DRIVER"))).andExpect(status().isForbidden());verifyNoInteractions(client);}
 @ParameterizedTest @ValueSource(strings={"WAREHOUSE_WORKER","DISPATCHER","MANAGER","ADMIN","COMPANY_ADMIN"}) void warehouseOperationalRolesKeepOwnList(String role)throws Exception{
  mvc.perform(get("/api/warehouse/tasks").header("Authorization",user(role))).andExpect(status().isOk());verify(warehouse).getTasks(1L,null,false);
 }
 @Test void warehouseAwaitingReplanQueryParamReachesService()throws Exception{
  mvc.perform(get("/api/warehouse/tasks").param("includeAwaitingReplan","true").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isOk());verify(warehouse).getTasks(1L,null,true);verify(warehouse,never()).getTasks(1L,null,false);
 }
 @Test void warehouseCannotRequestForeignCompanyOrWorker()throws Exception{
  mvc.perform(get("/api/warehouse/tasks").param("companyId","2").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/warehouse/tasks").param("workerId","22").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isForbidden());verifyNoInteractions(warehouse);
 }
 @Test void warehouseDetailsRequireOwnedTrip()throws Exception{
  mvc.perform(post("/api/warehouse/tasks/8/start").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isForbidden());verifyNoInteractions(warehouse);
 }
 @Test void owningWarehouseCanReadPlacementSteps()throws Exception{
  mvc.perform(post("/api/warehouse/tasks/7/start").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isOk());verify(warehouse).startLoading(7L,null);
 }
 @Test void placementConfirmCannotLeakForeignNextPlacement()throws Exception{
  mvc.perform(post("/api/warehouse/placements/31/confirm").contentType("application/json").content("{\"tripId\":8,\"method\":\"CODE\",\"code\":\"PK-31\"}").header("Authorization",user("WAREHOUSE_WORKER"))).andExpect(status().isForbidden());verifyNoInteractions(warehouse);
 }
 @Test void anonymousWarehouseAndDriverReadsAre401()throws Exception{
  for(String p:List.of("/api/warehouse/tasks","/api/driver/trips","/api/driver/trips/7/current-stop"))mvc.perform(get(p)).andExpect(status().isUnauthorized());verifyNoInteractions(warehouse,driver);
 }
 @Test void driverListAndStopRejectForeignObjects()throws Exception{
  mvc.perform(get("/api/driver/trips").param("driverId","22").header("Authorization",user("DRIVER"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/driver/trips/8/current-stop").header("Authorization",user("DRIVER"))).andExpect(status().isForbidden());verifyNoInteractions(driver);
 }
 @ParameterizedTest @ValueSource(strings={"DRIVER","DISPATCHER","MANAGER","ADMIN","COMPANY_ADMIN"}) void driverOperationalRolesKeepOwnReads(String role)throws Exception{
  mvc.perform(get("/api/driver/trips").param("driverId","11").header("Authorization",user(role))).andExpect(status().isOk());
  mvc.perform(get("/api/driver/trips/7/current-stop").header("Authorization",user(role))).andExpect(status().isOk());
 }
}
