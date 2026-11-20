package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Hai công ty thật trên H2, không giả repository: công cụ chỉ thấy dữ liệu của công ty và của người đang hỏi. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class TripsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private TripsTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        Company longBinh = company("AST-LB", "Vận tải Long Bình");
        Company phuongNam = company("AST-PN", "Vận tải Phương Nam");
        User dispatcher = user(longBinh, "ast-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        User khoa = user(longBinh, "ast-driver-khoa", UserRole.DRIVER, "Trần Minh Khoa");
        User tuan = user(longBinh, "ast-driver-tuan", UserRole.DRIVER, "Phạm Anh Tuấn");
        User otherDispatcher = user(phuongNam, "ast-dispatcher-pn", UserRole.DISPATCHER, "Võ Hải Đăng");
        Vehicle truck = vehicle(longBinh, "Xe tải 3,5 tấn", "51C-123.45");
        Vehicle otherTruck = vehicle(phuongNam, "Xe tải 5 tấn", "61C-678.90");

        Trip planned = trip(longBinh, truck, dispatcher, khoa, "AST-LB-001", "Biên Hoà — Thủ Đức", TripStatus.PLANNED);
        stop(planned, 1, "Kho Tân Vạn", LocalDateTime.of(2026, 11, 21, 9, 30));
        stop(planned, 2, "Siêu thị Thủ Đức", LocalDateTime.of(2026, 11, 21, 11, 0));
        OptimizationJob job = OptimizationJob.builder().trip(planned).jobUuid("ast-job-001").build();
        entityManager.persist(job);
        entityManager.persist(LoadPlan.builder().job(job).planName("Phương án A").candidateLabel("A")
                .objective("MAX_VOLUME").approved(false).stale(true).build());
        entityManager.persist(LoadPlan.builder().job(job).planName("Phương án C").candidateLabel("C")
                .objective("MIN_REHANDLING").approved(true).stale(true).build());

        trip(longBinh, truck, dispatcher, tuan, "AST-LB-002", "Long Thành — Nhơn Trạch", TripStatus.DRAFT);
        trip(phuongNam, otherTruck, otherDispatcher, null, "AST-PN-001", "Cần Thơ — Sóc Trăng", TripStatus.PLANNED);
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tripOfAnotherCompanyIsNotFound() {
        signIn("ast-dispatcher");

        assertThat(call(() -> tool.getTrip("AST-PN-001"))).isEqualTo("{\"error\":\"TRIP_NOT_FOUND\"}");
        assertThat(call(() -> tool.listTrips(null))).doesNotContain("AST-PN-001");
    }

    @Test
    void dispatcherListsEveryTripOfTheCompanyAndCanFilterByStatus() {
        signIn("ast-dispatcher");

        JsonNode all = JSON.readTree(call(() -> tool.listTrips(null)));
        JsonNode planned = JSON.readTree(call(() -> tool.listTrips("planned")));

        assertThat(all.get("total").asInt()).isEqualTo(2);
        assertThat(all.get("rows")).extracting(row -> row.get("tripCode").asString())
                .containsExactlyInAnyOrder("AST-LB-001", "AST-LB-002");
        assertThat(planned.get("total").asInt()).isEqualTo(1);
        assertThat(planned.get("rows").get(0).get("driverName").asString()).isEqualTo("Trần Minh Khoa");
        assertThat(planned.get("rows").get(0).get("licensePlate").asString()).isEqualTo("51C-123.45");
    }

    @Test
    void unknownStatusIsInvalidInput() {
        signIn("ast-dispatcher");

        assertThat(call(() -> tool.listTrips("lỗi thời"))).isEqualTo("{\"error\":\"INVALID_INPUT\"}");
    }

    @Test
    void stalePlanSaysStaleWithoutInventingAReason() {
        signIn("ast-dispatcher");

        String json = call(() -> tool.getTrip("AST-LB-001"));
        JsonNode trip = JSON.readTree(json);

        assertThat(trip.get("status").asString()).isEqualTo("PLANNED");
        assertThat(trip.get("stops")).extracting(stop -> stop.get("name").asString())
                .containsExactly("Kho Tân Vạn", "Siêu thị Thủ Đức");
        assertThat(trip.get("stops").get(0).get("plannedArrival").asString()).isEqualTo("2026-11-21T09:30:00");
        assertThat(trip.get("planCount").asInt()).isEqualTo(2);
        JsonNode newest = trip.get("plans").get(0);
        assertThat(newest.get("label").asString()).isEqualTo("C");
        assertThat(newest.get("approved").asBoolean()).isTrue();
        assertThat(newest.get("stale").asBoolean()).isTrue();
        assertThat(newest.has("staleReason")).isTrue();
        assertThat(newest.get("staleReason").isNull()).isTrue();
        assertThat(json).doesNotContain("latitude").doesNotContain("\"id\"").doesNotContain("placements");
    }

    @Test
    void driverSeesOnlyOwnTripsAndNoPlans() {
        signIn("ast-driver-khoa");

        JsonNode mine = JSON.readTree(call(() -> tool.listTrips(null)));
        JsonNode trip = JSON.readTree(call(() -> tool.getTrip("AST-LB-001")));

        assertThat(mine.get("total").asInt()).isEqualTo(1);
        assertThat(mine.get("rows").get(0).get("tripCode").asString()).isEqualTo("AST-LB-001");
        assertThat(trip.get("stops").size()).isEqualTo(2);
        assertThat(trip.has("plans")).isFalse();
        assertThat(trip.has("planCount")).isFalse();
    }

    @Test
    void driverAskingForSomeoneElsesTripGetsOnlyTheServiceError() {
        signIn("ast-driver-khoa");

        String json = call(() -> tool.getTrip("AST-LB-002"));

        assertThat(json).isEqualTo("{\"error\":\"TRIP_NOT_ASSIGNED_TO_DRIVER\"}");
    }

    /** Như vòng gọi công cụ: lỗi của service thành mã, không lộ dữ liệu. */
    private static String call(Supplier<Object> invocation) {
        try {
            return ToolResults.json(invocation.get());
        } catch (AppException e) {
            return ToolResults.error(e);
        }
    }

    private static void signIn(String keycloakId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(keycloakId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Company company(String code, String name) {
        Company company = Company.builder().companyCode(code).companyName(name).taxCode(code + "-TAX")
                .billingEmail(code.toLowerCase() + "@loadmaster.test").status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        entityManager.persist(company);
        return company;
    }

    private User user(Company company, String keycloakId, UserRole role, String fullName) {
        User user = User.builder().keycloakId(keycloakId).company(company).email(keycloakId + "@loadmaster.test")
                .fullName(fullName).userRoleType(role).status(UserStatus.ACTIVE).build();
        entityManager.persist(user);
        return user;
    }

    private Vehicle vehicle(Company company, String name, String plate) {
        VehicleType type = VehicleType.builder().company(company).name(name).innerLength(5200).innerWidth(2000)
                .innerHeight(2000).maxPayloadKg(new BigDecimal("3500")).build();
        entityManager.persist(type);
        Vehicle vehicle = Vehicle.builder().company(company).vehicleType(type).name(name).licensePlate(plate).build();
        entityManager.persist(vehicle);
        return vehicle;
    }

    private Trip trip(Company company, Vehicle vehicle, User dispatcher, User driver, String code, String name,
                      TripStatus status) {
        Trip trip = Trip.builder().company(company).vehicle(vehicle).createdByDispatcher(dispatcher).driver(driver)
                .tripCode(code).tripName(name).status(status).build();
        entityManager.persist(trip);
        return trip;
    }

    private void stop(Trip trip, int sequence, String name, LocalDateTime plannedArrival) {
        entityManager.persist(DeliveryStop.builder().trip(trip).stopSequence(sequence).stopName(name)
                .address(name + ", TP. Hồ Chí Minh").status(DeliveryStopStatus.PENDING)
                .plannedArrival(plannedArrival).build());
    }
}
