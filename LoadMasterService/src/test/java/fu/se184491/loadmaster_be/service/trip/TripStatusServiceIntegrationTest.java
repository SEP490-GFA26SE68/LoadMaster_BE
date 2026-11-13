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
        "spring.datasource.url=jdbc:h2:mem:trip-status;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
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
class TripStatusServiceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TripStatusService tripStatusService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private User actor;
    private Company company;
    private Vehicle vehicle;

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

        Trip transitioned = tripStatusService.transitionTo(trip.getId(), TripStatus.IN_TRANSIT, actor.getId());

        assertEquals(TripStatus.IN_TRANSIT, transitioned.getStatus());
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

        assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> tripStatusService.transitionTo(
                        trip.getId(), TripStatus.IN_TRANSIT, actor.getId()));
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
                        trip.getId(), TripStatus.IN_TRANSIT, actor.getId()));
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
                () -> tripStatusService.transitionTo(trip.getId(), TripStatus.IN_TRANSIT, actor.getId()));
    }

    private Trip persistTrip(TripStatus status, Map<String, Object> routePlan) {
        Trip trip = Trip.builder()
                .company(company)
                .vehicle(vehicle)
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
