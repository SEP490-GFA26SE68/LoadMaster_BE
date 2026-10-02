package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.client.GoongMapsClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:trip-planning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
@AutoConfigureMockMvc
@Transactional
class TripPlanningControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private GoongMapsClient goongMapsClient;

    private Company company;
    private User dispatcher;
    private User driver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .companyCode("TRIP-PLANNING")
                .companyName("Trip Planning Logistics")
                .taxCode("TRIP-PLANNING-TAX")
                .billingEmail("billing@trip-planning.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);
        dispatcher = persistUser("trip-planning-dispatcher", "dispatcher@trip-planning.test", UserRole.DISPATCHER);
        driver = persistUser("trip-planning-driver", "driver@trip-planning.test", UserRole.DRIVER);
        VehicleType vehicleType = VehicleType.builder()
                .company(company)
                .name("Planning Truck")
                .innerLength(6000)
                .innerWidth(2400)
                .innerHeight(2400)
                .maxPayloadKg(new BigDecimal("10000"))
                .build();
        entityManager.persist(vehicleType);
        vehicle = Vehicle.builder()
                .company(company)
                .vehicleType(vehicleType)
                .licensePlate("51C-PLAN-01")
                .build();
        entityManager.persist(vehicle);
        entityManager.flush();
    }

    @Test
    void dispatcherCreatesDraftTripWithVehicleAndDriver() throws Exception {
        mockMvc.perform(post("/api/trips")
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vehicleId": %d,
                                  "driverId": %d,
                                  "departureTime": "2099-10-03T08:00:00"
                                }
                                """.formatted(vehicle.getId(), driver.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.tripCode").value(
                        org.hamcrest.Matchers.matchesPattern("TRIP-\\d{8}-[A-F0-9]{8}")))
                .andExpect(jsonPath("$.vehicleId").value(vehicle.getId()))
                .andExpect(jsonPath("$.driverId").value(driver.getId()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void dispatcherAddsPackagesAndCreatesDeliveryStopFromRequirement() throws Exception {
        Trip trip = persistDraftTrip("TRIP-ADD-PACKAGES");
        CargoPackage cargoPackage = persistRequiredPackage(
                "PKG-ADD-01", HandlingClass.STANDARD, "District 1", "10.7750000", "106.7000000");

        mockMvc.perform(post("/api/trips/{id}/packages", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packageIds":[%d],"override":false}
                                """.formatted(cargoPackage.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageIds[0]").value(cargoPackage.getId()))
                .andExpect(jsonPath("$.stops[0].stopSequence").value(1))
                .andExpect(jsonPath("$.stops[0].address").value("District 1"))
                .andExpect(jsonPath("$.stops[0].latitude").value(10.775))
                .andExpect(jsonPath("$.stops[0].packageIds[0]").value(cargoPackage.getId()));
    }

    @Test
    void dispatcherRemovesPackageFromTrip() throws Exception {
        Trip trip = persistDraftTrip("TRIP-REMOVE-PACKAGE");
        CargoPackage cargoPackage = persistRequiredPackage(
                "PKG-REMOVE-01", HandlingClass.STANDARD, "District 3", "10.7800000", "106.6800000");
        var dispatcherJwt = jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                .authorities(() -> "TRIP_MANAGE");
        mockMvc.perform(post("/api/trips/{id}/packages", trip.getId())
                        .with(dispatcherJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packageIds":[%d],"override":false}
                                """.formatted(cargoPackage.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/trips/{id}/packages/{packageId}", trip.getId(), cargoPackage.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/trips/{id}/segregation", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isEmpty());
    }

    @Test
    void dispatcherOptimizesOwnedTripRoute() throws Exception {
        Trip trip = persistDraftTrip("TRIP-OPTIMIZE-ENDPOINT");
        trip.setRoutePlan(Map.of("originLat", 10.7000000, "originLng", 106.6000000));
        DeliveryStop stop = DeliveryStop.builder()
                .trip(trip)
                .stopSequence(1)
                .stopName("District 5")
                .address("District 5")
                .latitude(new BigDecimal("10.7540000"))
                .longitude(new BigDecimal("106.6630000"))
                .status(DeliveryStopStatus.PENDING)
                .build();
        entityManager.persist(stop);
        entityManager.flush();
        when(goongMapsClient.getDistanceMatrix(anyList(), anyList()))
                .thenReturn(matrix(new long[][]{{0, 600}, {600, 0}}));
        when(goongMapsClient.getDirections(any(), any()))
                .thenReturn(directions(route(4200, 540, "optimized-polyline")));

        mockMvc.perform(post("/api/trips/{id}/optimize-route", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedStops[0]").value(stop.getId()))
                .andExpect(jsonPath("$.totalTravelDurationSeconds").value(540))
                .andExpect(jsonPath("$.segments[0].polyline").value("optimized-polyline"));
    }

    @Test
    void dispatcherRefreshesEtaUsingCurrentTraffic() throws Exception {
        Trip trip = persistDraftTrip("TRIP-ETA-ENDPOINT");
        trip.setRoutePlan(Map.of("originLat", 10.7000000, "originLng", 106.6000000));
        DeliveryStop stop = DeliveryStop.builder()
                .trip(trip)
                .stopSequence(1)
                .stopName("District 7")
                .latitude(new BigDecimal("10.7300000"))
                .longitude(new BigDecimal("106.7200000"))
                .status(DeliveryStopStatus.PENDING)
                .build();
        entityManager.persist(stop);
        entityManager.flush();
        when(goongMapsClient.getDistanceMatrix(anyList(), anyList()))
                .thenReturn(matrix(new long[][]{{0, 600}, {600, 0}}));
        when(goongMapsClient.getDirections(any(), any()))
                .thenReturn(directions(route(4200, 540, "initial-traffic")))
                .thenReturn(directions(route(3000, 300, "current-traffic")));

        mockMvc.perform(post("/api/trips/{id}/optimize-route", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTravelDurationSeconds").value(540));

        mockMvc.perform(get("/api/trips/{id}/eta", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTravelDurationSeconds").value(300))
                .andExpect(jsonPath("$.segments[0].polyline").value("current-traffic"));
    }

    @Test
    void tripPlanningEndpointsRequireTripManageAuthority() throws Exception {
        mockMvc.perform(post("/api/trips")
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vehicleId": %d,
                                  "driverId": %d,
                                  "departureTime": "2099-10-03T08:00:00"
                                }
                                """.formatted(vehicle.getId(), driver.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void dispatcherCannotOptimizeAnotherCompanyTrip() throws Exception {
        Company otherCompany = Company.builder()
                .companyCode("OTHER-PLANNING")
                .companyName("Other Planning Logistics")
                .taxCode("OTHER-PLANNING-TAX")
                .billingEmail("billing@other-planning.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(otherCompany);
        Trip otherTrip = Trip.builder()
                .company(otherCompany)
                .tripCode("OTHER-COMPANY-TRIP")
                .departureTime(LocalDateTime.of(2099, 10, 3, 8, 0))
                .status(TripStatus.DRAFT)
                .build();
        entityManager.persist(otherTrip);
        entityManager.flush();

        mockMvc.perform(post("/api/trips/{id}/optimize-route", otherTrip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void addingConflictingPackageIsRejectedBySegregationCheck() throws Exception {
        Trip trip = persistDraftTrip("TRIP-SEGREGATION-CHECK");
        CargoPackage standard = persistRequiredPackage(
                "PKG-STANDARD-HTTP", HandlingClass.STANDARD, "District 8", "10.7240000", "106.6280000");
        CargoPackage fragile = persistRequiredPackage(
                "PKG-FRAGILE-HTTP", HandlingClass.FRAGILE, "District 9", "10.8200000", "106.7600000");

        mockMvc.perform(post("/api/trips/{id}/packages", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packageIds":[%d],"override":false}
                                """.formatted(standard.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/trips/{id}/packages", trip.getId())
                        .with(jwt().jwt(token -> token.subject(dispatcher.getKeycloakId()))
                                .authorities(() -> "TRIP_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packageIds":[%d],"override":false}
                                """.formatted(fragile.getId())))
                .andExpect(status().isConflict());
    }

    private Trip persistDraftTrip(String code) {
        Trip trip = Trip.builder()
                .company(company)
                .vehicle(vehicle)
                .tripCode(code)
                .departureTime(LocalDateTime.of(2099, 10, 3, 8, 0))
                .status(TripStatus.DRAFT)
                .build();
        entityManager.persist(trip);
        entityManager.flush();
        return trip;
    }

    private CargoPackage persistRequiredPackage(
            String barcode,
            HandlingClass handlingClass,
            String destination,
            String latitude,
            String longitude
    ) {
        TransportOrder order = TransportOrder.builder()
                .company(company)
                .orderCode("ORDER-" + barcode)
                .status(OrderStatus.PENDING)
                .build();
        entityManager.persist(order);
        CargoPackage cargoPackage = CargoPackage.builder()
                .order(order)
                .trackingBarcode(barcode)
                .actualWeightKg(BigDecimal.ONE)
                .handlingClass(handlingClass)
                .status(PackageStatus.PENDING)
                .build();
        entityManager.persist(cargoPackage);
        DeliveryRequirement requirement = DeliveryRequirement.builder()
                .company(company)
                .destination(destination)
                .destinationLat(new BigDecimal(latitude))
                .destinationLng(new BigDecimal(longitude))
                .deadline(LocalDateTime.of(2099, 10, 3, 12, 0))
                .priority(DeliveryRequirementPriority.NORMAL)
                .status(DeliveryRequirementStatus.PENDING)
                .createdBy(dispatcher)
                .packages(new LinkedHashSet<>(java.util.List.of(cargoPackage)))
                .build();
        entityManager.persist(requirement);
        entityManager.flush();
        return cargoPackage;
    }

    private User persistUser(String keycloakId, String email, UserRole role) {
        User user = User.builder()
                .keycloakId(keycloakId)
                .company(company)
                .email(email)
                .fullName(role.name())
                .userRoleType(role)
                .status(UserStatus.ACTIVE)
                .build();
        entityManager.persist(user);
        return user;
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
