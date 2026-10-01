package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cargo-segregation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
class CargoSegregationServiceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CargoSegregationService cargoSegregationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Company company;
    private Trip trip;
    private TransportOrder order;
    private TransportOrder unassignedOrder;
    private PackageType packageType;
    private VehicleType vehicleType;

    @BeforeEach
    void setUpTrip() {
        company = Company.builder()
                .companyCode("SEGREGATION")
                .companyName("Segregation Logistics")
                .taxCode("SEGREGATION-TAX")
                .billingEmail("billing@segregation.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);

        User dispatcher = User.builder()
                .keycloakId("segregation-dispatcher")
                .company(company)
                .email("dispatcher@segregation.test")
                .fullName("Segregation Dispatcher")
                .userRoleType(UserRole.DISPATCHER)
                .status(UserStatus.ACTIVE)
                .build();
        entityManager.persist(dispatcher);

        vehicleType = VehicleType.builder()
                .company(company)
                .name("General Truck")
                .innerLength(6000)
                .innerWidth(2400)
                .innerHeight(2400)
                .maxPayloadKg(new BigDecimal("10000"))
                .build();
        entityManager.persist(vehicleType);

        Vehicle vehicle = Vehicle.builder()
                .company(company)
                .vehicleType(vehicleType)
                .licensePlate("51C-SEG-01")
                .build();
        entityManager.persist(vehicle);

        trip = Trip.builder()
                .company(company)
                .vehicle(vehicle)
                .tripCode("TRIP-SEG-01")
                .status(TripStatus.DRAFT)
                .build();
        entityManager.persist(trip);

        DeliveryStop stop = DeliveryStop.builder()
                .trip(trip)
                .stopSequence(1)
                .stopName("Da Nang")
                .status(DeliveryStopStatus.PENDING)
                .build();
        entityManager.persist(stop);

        order = TransportOrder.builder()
                .company(company)
                .deliveryStop(stop)
                .orderCode("ORDER-SEG-01")
                .status(OrderStatus.ASSIGNED)
                .build();
        entityManager.persist(order);

        unassignedOrder = TransportOrder.builder()
                .company(company)
                .orderCode("ORDER-UNASSIGNED-01")
                .status(OrderStatus.PENDING)
                .build();
        entityManager.persist(unassignedOrder);

        packageType = PackageType.builder()
                .company(company)
                .typeCode("SEG-BOX")
                .name("Segregation Box")
                .length(100)
                .width(100)
                .height(100)
                .build();
        entityManager.persist(packageType);
    }

    @Test
    void groupsTripPackagesByHandlingClass() {
        CargoPackage standardOne = persistPackage("SEG-001", HandlingClass.STANDARD);
        CargoPackage standardTwo = persistPackage("SEG-002", HandlingClass.STANDARD);
        CargoPackage refrigerated = persistPackage("SEG-003", HandlingClass.REFRIGERATED);
        entityManager.flush();

        var result = cargoSegregationService.checkTrip(company.getId(), trip.getId());

        var standardGroup = result.getGroups().stream()
                .filter(group -> group.getHandlingClass() == HandlingClass.STANDARD)
                .findFirst()
                .orElseThrow();
        var refrigeratedGroup = result.getGroups().stream()
                .filter(group -> group.getHandlingClass() == HandlingClass.REFRIGERATED)
                .findFirst()
                .orElseThrow();
        assertEquals(2, result.getGroups().size());
        assertEquals(2, standardGroup.getPackageCount());
        assertEquals(List.of(standardOne.getId(), standardTwo.getId()), standardGroup.getPackageIds());
        assertEquals(1, refrigeratedGroup.getPackageCount());
        assertEquals(List.of(refrigerated.getId()), refrigeratedGroup.getPackageIds());
        assertEquals(List.of(), result.getConflicts());
    }

    @Test
    void reportsConflictWhenFragileAndStandardPackagesShareTrip() {
        CargoPackage standard = persistPackage("CONFLICT-STANDARD", HandlingClass.STANDARD);
        CargoPackage fragile = persistPackage("CONFLICT-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        var result = cargoSegregationService.checkTrip(company.getId(), trip.getId());

        assertEquals(1, result.getConflicts().size());
        assertEquals("FRAGILE_STANDARD_MIX", result.getConflicts().getFirst().getRuleCode());
        assertEquals(
                List.of(standard.getId(), fragile.getId()),
                result.getConflicts().getFirst().getAffectedPackageIds());
    }

    @Test
    void warnsWhenHazardousCargoUsesGeneralVehicle() {
        CargoPackage hazardous = persistPackage("HAZARDOUS-001", HandlingClass.HAZARDOUS);
        entityManager.flush();

        var result = cargoSegregationService.checkTrip(company.getId(), trip.getId());

        assertEquals(1, result.getConflicts().size());
        assertEquals("HAZARDOUS_VEHICLE_REQUIRED", result.getConflicts().getFirst().getRuleCode());
        assertEquals(List.of(hazardous.getId()), result.getConflicts().getFirst().getAffectedPackageIds());
    }

    @Test
    void acceptsHazardousCargoWhenVehicleIsCapable() {
        vehicleType.setHazardousCapable(true);
        persistPackage("HAZARDOUS-CAPABLE-001", HandlingClass.HAZARDOUS);
        entityManager.flush();

        var result = cargoSegregationService.checkTrip(company.getId(), trip.getId());

        assertEquals(List.of(), result.getConflicts());
    }

    @Test
    void dispatcherCanInspectTripSegregationOverHttp() throws Exception {
        persistPackage("HTTP-STANDARD", HandlingClass.STANDARD);
        persistPackage("HTTP-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        mockMvc.perform(get("/api/trips/{id}/segregation", trip.getId())
                        .with(jwt().jwt(token -> token.subject("segregation-dispatcher"))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].handlingClass").value("STANDARD"))
                .andExpect(jsonPath("$.groups[1].handlingClass").value("FRAGILE"))
                .andExpect(jsonPath("$.conflicts[0].ruleCode").value("FRAGILE_STANDARD_MIX"));
    }

    @Test
    void rejectsConflictingPackageAdditionWithoutOverride() {
        persistPackage("LOCKED-STANDARD", HandlingClass.STANDARD);
        CargoPackage fragileCandidate = persistUnassignedPackage(
                "CANDIDATE-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        AppException exception = assertThrows(
                AppException.class,
                () -> cargoSegregationService.validateAddition(
                        company.getId(),
                        trip.getId(),
                        List.of(fragileCandidate.getId()),
                        false,
                        null));

        assertEquals(ErrorCode.CARGO_SEGREGATION_CONFLICT, exception.getErrorCode());
    }

    @Test
    void overrideAllowsConflictingAdditionAndPersistsReason() {
        persistPackage("OVERRIDE-STANDARD", HandlingClass.STANDARD);
        CargoPackage fragileCandidate = persistUnassignedPackage(
                "OVERRIDE-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        cargoSegregationService.validateAddition(
                company.getId(),
                trip.getId(),
                List.of(fragileCandidate.getId()),
                true,
                "Customer-approved mixed cargo");

        assertEquals(
                "Customer-approved mixed cargo",
                cargoSegregationService.checkTrip(company.getId(), trip.getId()).getOverrideReason());
    }

    @Test
    void overrideRequiresReason() {
        persistPackage("REASON-STANDARD", HandlingClass.STANDARD);
        CargoPackage fragileCandidate = persistUnassignedPackage(
                "REASON-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        AppException exception = assertThrows(
                AppException.class,
                () -> cargoSegregationService.validateAddition(
                        company.getId(),
                        trip.getId(),
                        List.of(fragileCandidate.getId()),
                        true,
                        "  "));

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void firstPackageSetsTripHandlingClassLock() {
        CargoPackage fragileCandidate = persistUnassignedPackage(
                "FIRST-FRAGILE", HandlingClass.FRAGILE);
        entityManager.flush();

        cargoSegregationService.validateAddition(
                company.getId(),
                trip.getId(),
                List.of(fragileCandidate.getId()),
                false,
                null);

        assertEquals(
                "FRAGILE",
                cargoSegregationService.checkTrip(company.getId(), trip.getId()).getHandlingClassLock());
    }

    @Test
    void rejectsHazardousAdditionForGeneralVehicle() {
        CargoPackage hazardousCandidate = persistUnassignedPackage(
                "CANDIDATE-HAZARDOUS", HandlingClass.HAZARDOUS);
        entityManager.flush();

        AppException exception = assertThrows(
                AppException.class,
                () -> cargoSegregationService.validateAddition(
                        company.getId(),
                        trip.getId(),
                        List.of(hazardousCandidate.getId()),
                        false,
                        null));

        assertEquals(ErrorCode.CARGO_SEGREGATION_CONFLICT, exception.getErrorCode());
    }

    @Test
    void segregationEndpointRequiresTripManageAuthority() throws Exception {
        entityManager.flush();

        mockMvc.perform(get("/api/trips/{id}/segregation", trip.getId())
                        .with(jwt().jwt(token -> token.subject("segregation-dispatcher"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void dispatcherCannotInspectAnotherCompanyTrip() throws Exception {
        Company otherCompany = Company.builder()
                .companyCode("SEGREGATION-OTHER")
                .companyName("Other Segregation Logistics")
                .taxCode("SEGREGATION-OTHER-TAX")
                .billingEmail("billing@segregation-other.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(otherCompany);
        User otherDispatcher = User.builder()
                .keycloakId("other-segregation-dispatcher")
                .company(otherCompany)
                .email("dispatcher@segregation-other.test")
                .fullName("Other Segregation Dispatcher")
                .userRoleType(UserRole.DISPATCHER)
                .status(UserStatus.ACTIVE)
                .build();
        entityManager.persist(otherDispatcher);
        entityManager.flush();

        mockMvc.perform(get("/api/trips/{id}/segregation", trip.getId())
                        .with(jwt().jwt(token -> token.subject("other-segregation-dispatcher"))
                                .authorities(() -> "TRIP_MANAGE")))
                .andExpect(status().isNotFound());
    }

    private CargoPackage persistPackage(String barcode, HandlingClass handlingClass) {
        CargoPackage cargoPackage = CargoPackage.builder()
                .order(order)
                .packageType(packageType)
                .trackingBarcode(barcode)
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PLANNED)
                .handlingClass(handlingClass)
                .build();
        entityManager.persist(cargoPackage);
        return cargoPackage;
    }

    private CargoPackage persistUnassignedPackage(String barcode, HandlingClass handlingClass) {
        CargoPackage cargoPackage = CargoPackage.builder()
                .order(unassignedOrder)
                .packageType(packageType)
                .trackingBarcode(barcode)
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PENDING)
                .handlingClass(handlingClass)
                .build();
        entityManager.persist(cargoPackage);
        return cargoPackage;
    }
}
