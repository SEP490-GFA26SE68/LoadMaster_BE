package fu.se184491.loadmaster_be.service.vehicle;
import fu.se184491.loadmaster_be.service.company.WarehouseStaffAssignmentService;
import fu.se184491.loadmaster_be.security.TestJwtSupport;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static fu.se184491.loadmaster_be.service.vehicle.VehiclePresencePostgresTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** Owned PostgreSQL 17, real JWT filters, production service and check-in row locks. */
class WarehouseStaffAssignmentPostgresTest {
 static WarehouseStaffAssignmentService assignments;
 static final String BASE="/api/company-warehouses/101/staff-assignments";
 @BeforeAll static void start()throws Exception {VehiclePresencePostgresTest.start();assignments=ctx.getBean(WarehouseStaffAssignmentService.class);}
 @AfterAll static void stop(){VehiclePresencePostgresTest.stop();}
 @BeforeEach void setup(){new VehiclePresencePostgresTest().reset();sql.execute("INSERT INTO users VALUES(12,1,'Manager A','MANAGER','ACTIVE'),(13,2,'Manager B','MANAGER','ACTIVE'),(14,1,'Inactive manager','MANAGER','SUSPENDED'),(15,1,'Inactive worker','WAREHOUSE_WORKER','SUSPENDED')");as(12);}
 @AfterEach void clear(){org.springframework.security.test.context.TestSecurityContextHolder.clearContext();}
 static void as(long id){var jwt=Jwt.withTokenValue("synthetic").header("alg","none").subject(Long.toString(id)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));}
 String token(long id,String role)throws Exception{return "Bearer "+TestJwtSupport.userToken(Long.toString(id),role);}
 @Test void httpManagerCreatesAndReadsAudit()throws Exception {
  http.perform(post(BASE).header("Authorization",token(12,"MANAGER")).contentType("application/json").content("{\"userId\":10,\"companyId\":2,\"actorId\":13}")).andExpect(status().isOk());
  http.perform(get(BASE).header("Authorization",token(12,"MANAGER"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
  http.perform(get(BASE+"/history").header("Authorization",token(12,"MANAGER"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].actorId").value(12)).andExpect(jsonPath("$.data[0].newActive").value(true));
  assertEquals(1,sql.queryForObject("SELECT company_id FROM audit_logs",Integer.class));
 }
 @Test void everyEndpointRejectsWrongRolesAndMissingJwt()throws Exception {
  for(String role:List.of("DISPATCHER","WAREHOUSE_WORKER","ADMIN","SYSTEM_ADMIN")) {
   String auth=token(11,role);
   for(String path:List.of("/api/company-warehouses/staff",BASE,BASE+"/history")) http.perform(get(path).header("Authorization",auth)).andExpect(status().isForbidden());
   http.perform(post(BASE).header("Authorization",auth).contentType("application/json").content("{\"userId\":10}")).andExpect(status().isForbidden());
   http.perform(delete(BASE+"/7").header("Authorization",auth)).andExpect(status().isForbidden());
  }
  http.perform(get(BASE)).andExpect(status().isUnauthorized());
 }
 @Test void wrongAudienceAndStaleManagerProfileRejected()throws Exception {
  http.perform(get(BASE).header("Authorization","Bearer "+TestJwtSupport.token("wrong","loadmaster-web","12","12",List.of("MANAGER")))).andExpect(status().isUnauthorized());
  for(long id:List.of(11L,14L)) http.perform(post(BASE).header("Authorization",token(id,"MANAGER")).contentType("application/json").content("{\"userId\":10}")).andExpect(status().isForbidden());
 }
 @Test void tenantIsolationForAllReadsAndWrites()throws Exception {
  String auth=token(13,"MANAGER");
  for(String path:List.of(BASE,BASE+"/history")) http.perform(get(path).header("Authorization",auth)).andExpect(status().isForbidden());
  http.perform(delete(BASE+"/7").header("Authorization",auth)).andExpect(status().isForbidden());
  http.perform(post(BASE).header("Authorization",auth).contentType("application/json").content("{\"userId\":9}")).andExpect(status().isForbidden());
  as(12);assertThrows(RuntimeException.class,()->assignments.change(101,9,true));assertThrows(RuntimeException.class,()->assignments.change(201,7,true));
 }
 @Test void inactiveAndNonWorkerCannotBeAssigned()throws Exception {
  for(long staff:List.of(8L,11L,12L,15L)) http.perform(post(BASE).header("Authorization",token(12,"MANAGER")).contentType("application/json").content("{\"userId\":"+staff+"}")).andExpect(status().isConflict());
  sql.update("UPDATE company_warehouses SET is_active=false WHERE id=102");assertThrows(RuntimeException.class,()->assignments.change(102,7,true));assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs",Integer.class));
 }
 @Test void multiWarehouseDuplicateRevokeReactivateAndAudit() {
  assignments.change(102,7,true);assignments.change(102,7,true);assertEquals(2,sql.queryForObject("SELECT count(*) FROM warehouse_staff_assignments WHERE user_id=7",Integer.class));
  assignments.change(102,7,false);assignments.change(102,7,false);assignments.change(102,7,true);
  var history=assignments.history(102,0);assertEquals(3,history.size());assertEquals(false,history.getFirst().oldActive());assertTrue(history.getFirst().newActive());assertNull(history.getLast().oldActive());
 }
 @Test void revokeInactiveOrRoleChangedStaffAndInactiveWarehouseStillWorks() {
  sql.update("UPDATE users SET status='SUSPENDED',user_role_type='DRIVER' WHERE id=7");sql.update("UPDATE company_warehouses SET is_active=false WHERE id=101");assignments.change(101,7,false);assertFalse(store.assigned(7,1,101));assertEquals(1,assignments.history(101,0).size());
 }
 @Test void revokeImmediatelyDeniesNewAndReceiptRetryCheckIns() {
  as(7);UUID key=UUID.randomUUID();service.checkIn(101,6,key);as(12);assignments.change(101,7,false);as(7);assertThrows(RuntimeException.class,()->service.checkIn(101,6,key));assertThrows(RuntimeException.class,()->service.checkIn(101,2,UUID.randomUUID()));assertEquals(1,store.events(6,1).size());
 }
 @Test void concurrentCreatesHaveOneRowAndOneAudit()throws Exception {
  var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);String auth=token(12,"MANAGER");
  try{Callable<Boolean> action=()->{barrier.await(5,TimeUnit.SECONDS);http.perform(post("/api/company-warehouses/102/staff-assignments").header("Authorization",auth).contentType("application/json").content("{\"userId\":7}")).andExpect(status().isOk());return true;};var a=pool.submit(action);var b=pool.submit(action);assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
  assertEquals(1,assignments.history(102,0).size());assertEquals(1,sql.queryForObject("SELECT count(*) FROM warehouse_staff_assignments WHERE user_id=7 AND warehouse_id=102",Integer.class));
 }
 @Test void duplicateAndRevokedAssignmentHttpContract()throws Exception {
  String auth=token(12,"MANAGER");
  for(int n=0;n<2;n++) http.perform(post(BASE).header("Authorization",auth).contentType("application/json").content("{\"userId\":10}")).andExpect(status().isOk());
  as(12);assertEquals(1,assignments.history(101,0).size());
  for(int n=0;n<2;n++) http.perform(delete(BASE+"/10").header("Authorization",auth)).andExpect(status().isOk());
  as(12);assertEquals(2,assignments.history(101,0).size());
  http.perform(post("/api/warehouse/vehicle-presence/101/check-in").header("Authorization",token(10,"WAREHOUSE_WORKER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"vehicleId\":6}")).andExpect(status().isForbidden());
  http.perform(post(BASE).header("Authorization",auth).contentType("application/json").content("{\"userId\":9}")).andExpect(status().isForbidden());
 }
 @Test void auditFailureRollsBackAssignment() {
  sql.execute("ALTER TABLE audit_logs ADD CONSTRAINT synthetic_failure CHECK (action_type <> 'WAREHOUSE_STAFF_ASSIGNMENT')");try {assertThrows(RuntimeException.class,()->assignments.change(102,7,true));assertFalse(store.assigned(7,1,102));}finally{sql.execute("ALTER TABLE audit_logs DROP CONSTRAINT synthetic_failure");}
 }
 @Test void revokeFirstBlocksCheckInAfterCommit()throws Exception {
  var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {var revoke=pool.submit(()->{as(12);tx.executeWithoutResult(t->{assignments.change(101,7,false);locked.countDown();await(release);});});assertTrue(locked.await(5,TimeUnit.SECONDS));
   var attempted=new CountDownLatch(1);var check=pool.submit(()->{as(7);attempted.countDown();try{service.checkIn(101,6,UUID.randomUUID());return true;}catch(fu.se184491.loadmaster_be.exception.AppException e){return false;}});
   assertTrue(attempted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->check.get(200,TimeUnit.MILLISECONDS));release.countDown();revoke.get(10,TimeUnit.SECONDS);assertFalse(check.get(10,TimeUnit.SECONDS));assertEquals("UNKNOWN",store.presence(6,1).status());
  } finally {release.countDown();pool.shutdownNow();}
 }
 @Test void checkInFirstMakesRevokeWaitUntilCommit()throws Exception {
  var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {var check=pool.submit(()->{as(7);tx.executeWithoutResult(t->{service.checkIn(101,6,UUID.randomUUID());locked.countDown();await(release);});});assertTrue(locked.await(5,TimeUnit.SECONDS));
   var attempted=new CountDownLatch(1);var revoke=pool.submit(()->{as(12);attempted.countDown();assignments.change(101,7,false);});assertTrue(attempted.await(5,TimeUnit.SECONDS));
   assertThrows(TimeoutException.class,()->revoke.get(200,TimeUnit.MILLISECONDS));release.countDown();check.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS);assertEquals("AT_WAREHOUSE",store.presence(6,1).status());as(7);assertThrows(RuntimeException.class,()->service.checkIn(101,2,UUID.randomUUID()));
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void listStaffIsCompanyScopedAndHistoryPaginationValidated(){assertEquals(Set.of(15L,10L,7L),new HashSet<>(assignments.staff().stream().map(WarehouseStaffAssignmentService.Staff::userId).toList()));assertThrows(RuntimeException.class,()->assignments.history(101,-1));assertTrue(assignments.history(101,1).isEmpty());}
 static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
