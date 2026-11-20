package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.InvalidTripStatusTransitionException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret"
})
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TripStatusServiceIntegrationTest {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("trip_status_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        postgres.start();
        if (postgres.getMappedPort(5432) == 5432) throw new IllegalStateException("Private random port required");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate sql;
    @Autowired private VehiclePresenceService vehiclePresence;

    @BeforeAll
    void presenceSchema() throws java.io.IOException {
        // Existing reviewed fixtures, only in this new disposable PostgreSQL container.
        for (String resource : java.util.List.of("/sql/vehicle-current-presence-proposal.sql", "/sql/vehicle-warehouse-transfers.sql")) {
            try (var input = getClass().getResourceAsStream(resource)) {
                org.junit.jupiter.api.Assertions.assertNotNull(input);
                sql.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    @AfterAll static void stopDatabase() { postgres.stop(); }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private void authenticate(User user) {
        Jwt jwt = Jwt.withTokenValue("synthetic-test-token").header("alg", "RS256")
                .subject(user.getKeycloakId()).claim("company_id", company.getId())
                .claim("aud", java.util.List.of("loadmaster-api")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getUserRoleType().name()))));
    }


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TripStatusService tripStatusService;

    // Infrastructure outside this database scenario stays synthetic/offline.
    @MockitoBean
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnectionFactory;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private User actor;
    private Company company;
    private Vehicle vehicle;
    private User driver;
    private CompanyWarehouse warehouse;

    @BeforeEach
    void setUpActor() {
        // Chuyến cần công ty, xe và người lập (các cột NOT NULL của trips và vehicles).
        company = Company.builder()
                .companyCode("TRIP-STATUS")
                .companyName("Trip Status Logistics")
                .taxCode("TRIP-STATUS-TAX")
                .billingEmail("billing@trip-status.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);
        actor = User.builder()
                .keycloakId("trip-status-actor")
                .company(company)
                .email("trip-status@loadmaster.test")
                .fullName("Trip Status Actor")
                .userRoleType(UserRole.DISPATCHER)
                .status(UserStatus.ACTIVE)
                .build();
        entityManager.persist(actor);
        VehicleType vehicleType = VehicleType.builder()
                .company(company)
                .name("Status Truck")
                .innerLength(6000)
                .innerWidth(2400)
                .innerHeight(2400)
                .maxPayloadKg(new BigDecimal("10000"))
                .build();
        entityManager.persist(vehicleType);
        vehicle = Vehicle.builder()
                .company(company)
                .vehicleType(vehicleType)
                .name("Status Truck 01")
                .licensePlate("51C-STATUS-01")
                .build();
        entityManager.persist(vehicle);
        warehouse = CompanyWarehouse.builder().company(company).warehouseName("Status warehouse")
                .address("Synthetic test address").build();
        entityManager.persist(warehouse);
        User worker = User.builder().keycloakId("status-worker").company(company)
                .email("worker@status.test").fullName("Status Worker")
                .userRoleType(UserRole.WAREHOUSE_WORKER).status(UserStatus.ACTIVE).build();
        entityManager.persist(worker);
        driver = User.builder().keycloakId("status-driver").company(company)
                .email("driver@status.test").fullName("Status Driver")
                .userRoleType(UserRole.DRIVER).status(UserStatus.ACTIVE).build();
        entityManager.persist(driver);
        entityManager.flush();
        sql.update("INSERT INTO warehouse_staff_assignments(user_id,company_id,warehouse_id,active) VALUES(?,?,?,true)",
                worker.getId(), company.getId(), warehouse.getId());
        authenticate(worker);
        vehiclePresence.checkIn(warehouse.getId(), vehicle.getId(), java.util.UUID.randomUUID());
        authenticate(driver);
    }

    @Test
    void optimizedDraftTripCanBePlannedAndRecordsAudit() {
        Trip trip = persistTrip(TripStatus.DRAFT, Map.of("orderedStops", java.util.List.of(1L)));

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.PLANNED, actor.getId());

        assertEquals(TripStatus.PLANNED, transitioned.getStatus());
        AuditLog audit = entityManager.createQuery(
                        "select a from AuditLog a where a.entityName = 'Trip' and a.entityId = :tripId",
                        AuditLog.class)
                .setParameter("tripId", trip.getId().toString())
                .getSingleResult();
        assertEquals("TRIP_STATUS_TRANSITION", audit.getActionType());
        assertEquals(Map.of("status", "DRAFT"), audit.getOldValues());
        assertEquals(Map.of("status", "PLANNED"), audit.getNewValues());
        assertEquals(actor.getId(), audit.getUser().getId());
    }

    @Test
    void plannedTripWithLoadingExecutionCanStartLoading() {
        Trip trip = persistTrip(TripStatus.PLANNED, Map.of("orderedStops", java.util.List.of(1L)));
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid("status-job").build();
        entityManager.persist(job);
        LoadPlan plan = LoadPlan.builder().job(job).planName("Approved plan").approved(true).build();
        entityManager.persist(plan);
        entityManager.persist(LoadingExecution.builder()
                .loadPlan(plan)
                .worker(actor)
                .status(LoadingExecutionStatus.IN_PROGRESS)
                .build());
        entityManager.flush();

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.LOADING, actor.getId());

        assertEquals(TripStatus.LOADING, transitioned.getStatus());
    }

    @Test
    void loadingTripCanEnterTransitAfterLoadingCompletes() {
        Trip trip = persistTrip(TripStatus.LOADING, Map.of("orderedStops", java.util.List.of(1L)));
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid("completed-loading-job").build();
        entityManager.persist(job);
        LoadPlan plan = LoadPlan.builder().job(job).planName("Completed loading plan").approved(true).build();
        entityManager.persist(plan);
        entityManager.persist(LoadingExecution.builder()
                .loadPlan(plan)
                .worker(actor)
                .status(LoadingExecutionStatus.COMPLETED)
                .build());
        entityManager.flush();

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.IN_TRANSIT, driver.getId());

        assertEquals(TripStatus.IN_TRANSIT, transitioned.getStatus());
        assertEquals("IN_TRANSIT", sql.queryForObject("SELECT status FROM vehicle_current_presence WHERE vehicle_id=?", String.class, vehicle.getId()));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM vehicle_presence_events WHERE vehicle_id=? AND event_type='CHECK_OUT'", Integer.class, vehicle.getId()));
    }

    @Test
    void inTransitTripCanBeDeliveredAfterEveryStopCompletes() {
        Trip trip = persistTrip(TripStatus.IN_TRANSIT, Map.of("orderedStops", java.util.List.of(1L, 2L)));
        entityManager.persist(DeliveryStop.builder()
                .trip(trip)
                .stopSequence(1)
                .stopName("First completed stop")
                .status(DeliveryStopStatus.COMPLETED)
                .build());
        entityManager.persist(DeliveryStop.builder()
                .trip(trip)
                .stopSequence(2)
                .stopName("Last completed stop")
                .status(DeliveryStopStatus.COMPLETED)
                .build());
        entityManager.flush();

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.DELIVERED, actor.getId());

        assertEquals(TripStatus.DELIVERED, transitioned.getStatus());
    }

    @Test
    void unfinishedTripCanBeCancelled() {
        Trip trip = persistTrip(TripStatus.LOADING, Map.of("orderedStops", java.util.List.of(1L)));

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.CANCELLED, actor.getId());

        assertEquals(TripStatus.CANCELLED, transitioned.getStatus());
    }

    @Test
    void tripCannotSkipAState() {
        Trip trip = persistTrip(TripStatus.DRAFT, Map.of("orderedStops", java.util.List.of(1L)));
        trip.setDriver(driver);
        entityManager.flush();

        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(
                        trip.getId(), TripStatus.IN_TRANSIT, driver.getId()));
        assertEquals(TripStatus.DRAFT, trip.getStatus());
    }

    @Test
    void draftTripWithoutOptimizedRouteCannotBePlanned() {
        Trip trip = persistTrip(TripStatus.DRAFT, null);

        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(
                        trip.getId(), TripStatus.PLANNED, actor.getId()));
    }

    @Test
    void loadingTripCannotEnterTransitWhileLoadingIsIncomplete() {
        Trip trip = persistTrip(TripStatus.LOADING, Map.of("orderedStops", java.util.List.of(1L)));
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid("incomplete-loading-job").build();
        entityManager.persist(job);
        LoadPlan plan = LoadPlan.builder().job(job).planName("Incomplete loading plan").approved(true).build();
        entityManager.persist(plan);
        entityManager.persist(LoadingExecution.builder()
                .loadPlan(plan)
                .worker(actor)
                .status(LoadingExecutionStatus.IN_PROGRESS)
                .build());
        entityManager.flush();

        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(
                        trip.getId(), TripStatus.IN_TRANSIT, driver.getId()));
    }

    @Test
    void inTransitTripCannotBeDeliveredWithPendingStop() {
        Trip trip = persistTrip(TripStatus.IN_TRANSIT, Map.of("orderedStops", java.util.List.of(1L)));
        entityManager.persist(DeliveryStop.builder()
                .trip(trip)
                .stopSequence(1)
                .stopName("Pending stop")
                .status(DeliveryStopStatus.PENDING)
                .build());
        entityManager.flush();

        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(
                        trip.getId(), TripStatus.DELIVERED, actor.getId()));
    }

    @Test
    void lockForTransitionReadsTheStatusAnotherCommandJustWrote() {
        // Có tuyến (cột JSON): flush trước khi khoá sẽ ghi lại chuyến cũ đè lên trạng thái mới
        Trip trip = persistTrip(TripStatus.LOADING, Map.of("orderedStops", java.util.List.of(1L)));
        // Lệnh khác (huỷ) vừa ghi thẳng vào DB, chuyến trong bộ nhớ vẫn là LOADING
        entityManager.createNativeQuery("update trips set status = 'CANCELLED' where id = :id")
                .setParameter("id", trip.getId())
                .executeUpdate();
        assertEquals(TripStatus.LOADING, trip.getStatus());

        Trip locked = tripStatusService.lockForTransition(trip.getId());

        assertEquals(TripStatus.CANCELLED, locked.getStatus());
        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(trip.getId(), TripStatus.IN_TRANSIT, driver.getId()));
    }

    private Trip persistTrip(TripStatus status, Map<String, Object> routePlan) {
        Trip trip = Trip.builder()
                .company(company)
                .vehicle(vehicle)
                .originWarehouse(warehouse)
                .driver(status == TripStatus.LOADING ? driver : null)
                .createdByDispatcher(actor)
                .tripCode("STATUS-" + status)
                .status(status)
                .routePlan(routePlan)
                .build();
        entityManager.persist(trip);
        entityManager.flush();
        return trip;
    }
}
