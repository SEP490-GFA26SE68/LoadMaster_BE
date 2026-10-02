package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.route.RouteOptimizationResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.MissingCoordinatesException;
import fu.se184491.loadmaster_be.exception.RouteOptimizationUnavailableException;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:route-optimization;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
class RouteOptimizationServiceIntegrationTest {

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 10, 3, 8, 0);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RouteOptimizationService routeOptimizationService;

    @MockitoBean
    private GoongMapsClient goongMapsClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Trip trip;
    private DeliveryStop firstStop;
    private DeliveryStop secondStop;

    @BeforeEach
    void setUpTrip() {
        trip = Trip.builder()
                .tripCode("ROUTE-OPT-01")
                .departureTime(DEPARTURE)
                .status(TripStatus.DRAFT)
                .routePlan(Map.of("originLat", 10.7000000, "originLng", 106.6000000))
                .build();
        entityManager.persist(trip);

        firstStop = persistStop(1, "First", "10.8000000", "106.7000000");
        secondStop = persistStop(2, "Second", "10.7500000", "106.6500000");
        persistPackage(firstStop, "PKG-FIRST", DEPARTURE.plusHours(3));
        persistPackage(secondStop, "PKG-SECOND", DEPARTURE.plusHours(2));
        entityManager.flush();
    }

    @Test
    void optimizesRouteAndPersistsPlanStopScheduleAndAudit() {
        when(goongMapsClient.getDistanceMatrix(anyList(), anyList()))
                .thenReturn(matrix(new long[][]{
                        {0, 600, 300},
                        {600, 0, 240},
                        {300, 240, 0}
                }));
        when(goongMapsClient.getDirections(any(), any()))
                .thenReturn(directions(route(4000, 320, "slow"), route(3500, 280, "origin-second")))
                .thenReturn(directions(route(2800, 250, "slow"), route(2500, 200, "second-first")));

        RouteOptimizationResult result = routeOptimizationService.optimizeRoute(trip.getId());

        assertEquals(List.of(secondStop.getId(), firstStop.getId()), result.getOrderedStops());
        assertEquals(480, result.getTotalTravelDurationSeconds());
        assertEquals(6000, result.getTotalDistanceMeters());
        assertEquals(2280, result.getEstimatedTotalDurationSeconds());
        assertEquals(List.of("origin-second", "second-first"),
                result.getSegments().stream().map(RouteOptimizationResult.RouteSegment::polyline).toList());

        entityManager.flush();
        entityManager.clear();

        DeliveryStop reloadedSecond = entityManager.find(DeliveryStop.class, secondStop.getId());
        DeliveryStop reloadedFirst = entityManager.find(DeliveryStop.class, firstStop.getId());
        assertEquals(1, reloadedSecond.getStopSequence());
        assertEquals(DEPARTURE.plusSeconds(280 + 900), reloadedSecond.getPlannedArrival());
        assertEquals(2, reloadedFirst.getStopSequence());
        assertEquals(DEPARTURE.plusSeconds(280 + 900 + 200 + 900), reloadedFirst.getPlannedArrival());

        Trip reloadedTrip = entityManager.find(Trip.class, trip.getId());
        assertEquals(List.of(secondStop.getId().intValue(), firstStop.getId().intValue()),
                reloadedTrip.getRoutePlan().get("orderedStops"));
        AuditLog audit = entityManager.createQuery(
                        "select a from AuditLog a where a.entityName = 'Trip' and a.entityId = :tripId",
                        AuditLog.class)
                .setParameter("tripId", trip.getId().toString())
                .getSingleResult();
        assertEquals("ROUTE_OPTIMIZED", audit.getActionType());
    }

    @Test
    void rejectsAStopWithoutCoordinates() {
        firstStop.setLatitude(null);
        entityManager.flush();

        assertThrows(
                MissingCoordinatesException.class,
                () -> routeOptimizationService.optimizeRoute(trip.getId()));
    }

    @Test
    void reportsRouteOptimizationUnavailableWhenGoongIsUnavailable() {
        when(goongMapsClient.getDistanceMatrix(anyList(), anyList()))
                .thenThrow(new ServiceUnavailableException("Goong unavailable", new RuntimeException()));

        assertThrows(
                RouteOptimizationUnavailableException.class,
                () -> routeOptimizationService.optimizeRoute(trip.getId()));
    }

    private DeliveryStop persistStop(int sequence, String name, String latitude, String longitude) {
        DeliveryStop stop = DeliveryStop.builder()
                .trip(trip)
                .stopSequence(sequence)
                .stopName(name)
                .latitude(new BigDecimal(latitude))
                .longitude(new BigDecimal(longitude))
                .status(DeliveryStopStatus.PENDING)
                .build();
        entityManager.persist(stop);
        return stop;
    }

    private void persistPackage(DeliveryStop stop, String barcode, LocalDateTime deadline) {
        TransportOrder order = TransportOrder.builder()
                .deliveryStop(stop)
                .orderCode("ORDER-" + barcode)
                .timeWindowEnd(deadline)
                .status(OrderStatus.ASSIGNED)
                .build();
        entityManager.persist(order);
        entityManager.persist(CargoPackage.builder()
                .order(order)
                .trackingBarcode(barcode)
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PLANNED)
                .build());
    }

    private static DistanceMatrixResponse matrix(long[][] durations) {
        return new DistanceMatrixResponse(java.util.Arrays.stream(durations)
                .map(row -> new DistanceMatrixResponse.Row(java.util.Arrays.stream(row)
                        .mapToObj(value -> new DistanceMatrixResponse.Element(
                                "OK", new DistanceMatrixResponse.Measure("", value),
                                new DistanceMatrixResponse.Measure("", value * 10)))
                        .toList()))
                .toList());
    }

    private static DirectionsResponse directions(DirectionsResponse.Route... routes) {
        return new DirectionsResponse(List.of(routes));
    }

    private static DirectionsResponse.Route route(long distance, long duration, String polyline) {
        return new DirectionsResponse.Route(
                List.of(new DirectionsResponse.Leg(
                        new DistanceMatrixResponse.Measure("", distance),
                        new DistanceMatrixResponse.Measure("", duration))),
                new DirectionsResponse.OverviewPolyline(polyline));
    }
}
