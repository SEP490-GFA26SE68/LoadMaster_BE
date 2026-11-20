package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.optimization.UnplacedReason;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.trip.TripService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hai công ty thật trên H2: công cụ phương án chỉ đọc phương án của công ty người hỏi, dữ kiện của kiện là số tính được
 * từ toạ độ (thùng dài 520 cm, kỳ vọng tính tay), trường không có số thì vắng hẳn.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-load-plans;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class LoadPlanToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private LoadPlanTools tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    private Company longBinh;
    private Long planC;
    private Long otherCompanyPlan;
    private Long bigPlan;
    private int packageSeq;

    @BeforeEach
    void seed() {
        longBinh = company("LPT-LB", "Vận tải Long Bình");
        Company phuongNam = company("LPT-PN", "Vận tải Phương Nam");
        User dispatcher = user(longBinh, "lpt-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        User otherDispatcher = user(phuongNam, "lpt-dispatcher-pn", UserRole.DISPATCHER, "Võ Hải Đăng");
        Vehicle truck = vehicle(longBinh, "Xe tải 3,5 tấn", "51C-111.22");
        Vehicle otherTruck = vehicle(phuongNam, "Xe tải 5 tấn", "61C-333.44");

        Trip trip = trip(longBinh, truck, dispatcher, "LPT-LB-001");
        DeliveryStop first = stop(trip, 1, "Kho Tân Vạn");
        DeliveryStop second = stop(trip, 2, "Siêu thị Thủ Đức");

        OptimizationJob oldRun = job(trip, "lpt-job-old");
        plan(oldRun, "A", "MAX_VOLUME", true);
        OptimizationJob latestRun = job(trip, "lpt-job-new");
        plan(latestRun, "A", "MAX_VOLUME", false);
        plan(latestRun, "B", "AXLE_BALANCE", false);
        LoadPlan c = plan(latestRun, "C", "MIN_REHANDLING", false);
        planC = c.getId();
        // A nằm sàn ở vách trước, B đặt lên A (cùng giao điểm 2), N sát cửa giao điểm 1 và đã ghim.
        place(c, cargo(longBinh, "PK-A", 40), second, 0, 0, 0, 60, 40, 40, 1, false);
        place(c, cargo(longBinh, "PK-B", 10), second, 10, 0, 40, 40, 40, 30, 2, false);
        place(c, cargo(longBinh, "PK-N", 25), first, 400, 0, 0, 60, 40, 40, 3, true);
        entityManager.persist(UnplacedPackage.builder().loadPlan(c).cargoPackage(cargo(longBinh, "PK-U", 900))
                .rejectionReason(UnplacedReason.OUT_OF_SPACE).build());

        Trip big = trip(longBinh, truck, dispatcher, "LPT-LB-002");
        DeliveryStop only = stop(big, 1, "Kho Long Thành");
        LoadPlan crowded = plan(job(big, "lpt-job-big"), "A", "MAX_VOLUME", false);
        bigPlan = crowded.getId();
        for (int i = 1; i <= 300; i++) {
            place(crowded, cargo(longBinh, "PK-BIG-" + i, i), only, (i % 10) * 50, 0, 0, 50, 50, 50, i, false);
        }

        Trip otherTrip = trip(phuongNam, otherTruck, otherDispatcher, "LPT-PN-001");
        DeliveryStop otherStop = stop(otherTrip, 1, "Cần Thơ");
        LoadPlan foreign = plan(job(otherTrip, "lpt-job-pn"), "A", "MAX_VOLUME", false);
        otherCompanyPlan = foreign.getId();
        place(foreign, cargo(phuongNam, "PK-PN-1", 5), otherStop, 0, 0, 0, 20, 20, 20, 1, false);
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void planOfAnotherCompanyIsNotFound() {
        signIn("lpt-dispatcher");

        assertThat(call(() -> tool.getLoadPlan(otherCompanyPlan))).isEqualTo("{\"error\":\"LOAD_PLAN_NOT_FOUND\"}");
        assertThat(call(() -> tool.getPackageFacts(otherCompanyPlan, "PK-PN-1")))
                .isEqualTo("{\"error\":\"LOAD_PLAN_NOT_FOUND\"}");
        assertThat(call(() -> tool.findPackages(otherCompanyPlan, LoadPlanTools.PackageFilter.HEAVIEST, null)))
                .isEqualTo("{\"error\":\"LOAD_PLAN_NOT_FOUND\"}");
        assertThat(call(() -> tool.comparePlans("LPT-PN-001"))).isEqualTo("{\"error\":\"TRIP_NOT_FOUND\"}");
    }

    @Test
    void packageOutsideThePlanHasItsOwnErrorCode() {
        signIn("lpt-dispatcher");

        assertThat(call(() -> tool.getPackageFacts(planC, "KHONG-CO"))).isEqualTo("{\"error\":\"PACKAGE_NOT_IN_PLAN\"}");
        assertThat(call(() -> tool.getPackageFacts(planC, "PK-PN-1"))).isEqualTo("{\"error\":\"PACKAGE_NOT_IN_PLAN\"}");
    }

    @Test
    void vehicleWithoutAxleLimitsGivesNoLimitKeysAndNoZero() {
        signIn("lpt-dispatcher");

        String json = call(() -> tool.getLoadPlan(planC));
        JsonNode plan = JSON.readTree(json);

        assertThat(plan.get("tripCode").asString()).isEqualTo("LPT-LB-001");
        assertThat(plan.get("label").asString()).isEqualTo("C");
        assertThat(plan.get("volumePercent").asDouble()).isEqualTo(87.4);
        assertThat(plan.get("frontAxleLoadKg").asDouble()).isEqualTo(1200.0);
        assertThat(plan.has("frontAxleLimitKg")).isFalse();
        assertThat(plan.has("rearAxleLimitKg")).isFalse();
        assertThat(json).doesNotContain("LimitKg");
        assertThat(plan.get("placedCount").asInt()).isEqualTo(3);
        assertThat(plan.get("unplacedCount").asInt()).isEqualTo(1);
        assertThat(plan.get("pinnedCount").asInt()).isEqualTo(1);
        assertThat(plan.get("unplacedByReason").get(0).get("reason").asString()).isEqualTo("OUT_OF_SPACE");
        assertThat(plan.get("unplacedByReason").get(0).get("samplePackageCodes").get(0).asString()).isEqualTo("PK-U");
    }

    @Test
    void factsAreComputedFromTheCoordinates() {
        signIn("lpt-dispatcher");

        JsonNode onTop = JSON.readTree(call(() -> tool.getPackageFacts(planC, "pk-b")));
        JsonNode base = JSON.readTree(call(() -> tool.getPackageFacts(planC, "PK-A")));
        JsonNode nearDoor = JSON.readTree(call(() -> tool.getPackageFacts(planC, "PK-N")));
        JsonNode unplaced = JSON.readTree(call(() -> tool.getPackageFacts(planC, "PK-U")));

        assertThat(onTop.get("packageCode").asString()).isEqualTo("PK-B");
        assertThat(onTop.get("restsOn")).extracting(JsonNode::asString).containsExactly("PK-A");
        assertThat(onTop.get("onFloor").asBoolean()).isFalse();
        assertThat(onTop.get("stopSequence").asInt()).isEqualTo(2);
        assertThat(onTop.get("totalStops").asInt()).isEqualTo(2);
        assertThat(onTop.get("unloadRank").asInt()).isEqualTo(2);
        assertThat(onTop.get("unloadTotal").asInt()).isEqualTo(3);
        assertThat(base.get("restingOnIt")).extracting(JsonNode::asString).containsExactly("PK-B");
        assertThat(base.get("onFloor").asBoolean()).isTrue();
        assertThat(nearDoor.get("distanceToDoorCm").asDouble()).isEqualTo(60.0);
        assertThat(nearDoor.get("fromFrontWallCm").asDouble()).isEqualTo(400.0);
        assertThat(nearDoor.get("loadingStep").asInt()).isEqualTo(3);
        assertThat(nearDoor.get("totalSteps").asInt()).isEqualTo(3);
        assertThat(nearDoor.get("unloadRank").asInt()).isEqualTo(1);
        assertThat(nearDoor.get("pinned").asBoolean()).isTrue();
        assertThat(nearDoor.has("outOfZone")).isFalse();
        assertThat(unplaced.get("placed").asBoolean()).isFalse();
        assertThat(unplaced.get("reason").asString()).isEqualTo("OUT_OF_SPACE");
        assertThat(unplaced.has("restsOn")).isFalse();
    }

    @Test
    void heaviestOfThreeHundredIsTwentyRowsHeaviestFirst() {
        signIn("lpt-dispatcher");

        JsonNode page = JSON.readTree(call(() -> tool.findPackages(bigPlan, LoadPlanTools.PackageFilter.HEAVIEST, null)));

        assertThat(page.get("total").asInt()).isEqualTo(300);
        assertThat(page.get("rows").size()).isEqualTo(20);
        List<Double> weights = new ArrayList<>();
        page.get("rows").forEach(row -> weights.add(row.get("weightKg").asDouble()));
        assertThat(weights.get(0)).isEqualTo(300.0);
        assertThat(weights.get(19)).isEqualTo(281.0);
        assertThat(weights).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void filtersSayWhenTheyCannotAnswer() {
        signIn("lpt-dispatcher");

        assertThat(call(() -> tool.findPackages(planC, LoadPlanTools.PackageFilter.BY_STOP, null)))
                .isEqualTo("{\"error\":\"INVALID_INPUT\"}");
        assertThat(call(() -> tool.findPackages(planC, LoadPlanTools.PackageFilter.OUT_OF_ZONE, null)))
                .isEqualTo("{\"error\":\"ZONES_NOT_AVAILABLE\"}");
        JsonNode byStop = JSON.readTree(call(() -> tool.findPackages(planC, LoadPlanTools.PackageFilter.BY_STOP, 2)));
        assertThat(byStop.get("rows")).extracting(row -> row.get("packageCode").asString())
                .containsExactly("PK-B", "PK-A");
        JsonNode pinned = JSON.readTree(call(() -> tool.findPackages(planC, LoadPlanTools.PackageFilter.PINNED, null)));
        assertThat(pinned.get("total").asInt()).isEqualTo(1);
        JsonNode unplaced = JSON.readTree(call(() -> tool.findPackages(planC, LoadPlanTools.PackageFilter.UNPLACED, null)));
        assertThat(unplaced.get("rows").get(0).get("reason").asString()).isEqualTo("OUT_OF_SPACE");
    }

    @Test
    void compareShowsOnlyTheLatestRunWithPlanIds() {
        signIn("lpt-dispatcher");

        JsonNode comparison = JSON.readTree(call(() -> tool.comparePlans("LPT-LB-001")));

        assertThat(comparison.get("runCount").asInt()).isEqualTo(2);
        assertThat(comparison.get("plans")).extracting(plan -> plan.get("label").asString())
                .containsExactly("A", "B", "C");
        assertThat(comparison.get("plans").get(2).get("loadPlanId").asLong()).isEqualTo(planC);
        assertThat(comparison.get("plans").get(0).has("unplacedByReason")).isTrue();
        assertThat(comparison.get("plans").get(2).get("unplacedByReason").get(0).has("samplePackageCodes")).isFalse();
    }

    /** Dịch vụ tối ưu ghi cả lý do ngoài enum Java (cột của JPA là enum nên dựng phương án giả để có chuỗi đó). */
    @Test
    void reasonOutsideTheJavaEnumIsReturnedAsIs() {
        LoadPlanQueryService plans = mock(LoadPlanQueryService.class);
        TripService trips = mock(TripService.class);
        LoadPlanDetailResponse plan = LoadPlanDetailResponse.builder().id(9L).tripId(7L).placements(List.of())
                .unplaced(List.of(UnplacedPackageResponse.builder().packageCode("PK-X").reason("NO_SPACE")
                        .weightKg(new BigDecimal("12")).build()))
                .build();
        when(plans.getPlan(9L)).thenReturn(plan);
        when(trips.getTripDetail(7L)).thenReturn(new TripDetailResponse(null, List.of()));
        LoadPlanTools isolated = new LoadPlanTools(mock(CurrentUserService.class), trips,
                mock(TripLookupHelper.class), plans);

        JsonNode summary = JSON.readTree(call(() -> isolated.getLoadPlan(9L)));
        JsonNode facts = JSON.readTree(call(() -> isolated.getPackageFacts(9L, "PK-X")));

        assertThat(summary.get("unplacedByReason").get(0).get("reason").asString()).isEqualTo("NO_SPACE");
        assertThat(facts.get("reason").asString()).isEqualTo("NO_SPACE");
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

    /** Loại xe không khai giới hạn trục. Kích thước lòng thùng là cm. */
    private Vehicle vehicle(Company company, String name, String plate) {
        VehicleType type = VehicleType.builder().company(company).name(name).innerLength(520).innerWidth(200)
                .innerHeight(200).maxPayloadKg(new BigDecimal("3500")).build();
        entityManager.persist(type);
        Vehicle vehicle = Vehicle.builder().company(company).vehicleType(type).name(name).licensePlate(plate).build();
        entityManager.persist(vehicle);
        return vehicle;
    }

    private Trip trip(Company company, Vehicle vehicle, User dispatcher, String code) {
        Trip trip = Trip.builder().company(company).vehicle(vehicle).createdByDispatcher(dispatcher)
                .tripCode(code).tripName(code).status(TripStatus.PLANNED).build();
        entityManager.persist(trip);
        return trip;
    }

    private DeliveryStop stop(Trip trip, int sequence, String name) {
        DeliveryStop stop = DeliveryStop.builder().trip(trip).stopSequence(sequence).stopName(name)
                .address(name + ", TP. Hồ Chí Minh").status(DeliveryStopStatus.PENDING)
                .plannedArrival(LocalDateTime.of(2026, 11, 21, 9 + sequence, 0)).build();
        entityManager.persist(stop);
        return stop;
    }

    private OptimizationJob job(Trip trip, String uuid) {
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid(uuid).build();
        entityManager.persist(job);
        return job;
    }

    /** Phương án không có số dỡ-xếp lại nên không dựng lại được vùng điểm giao. */
    private LoadPlan plan(OptimizationJob job, String label, String objective, boolean stale) {
        LoadPlan plan = LoadPlan.builder().job(job).planName("Phương án " + label).candidateLabel(label)
                .objective(objective).approved(false).stale(stale).manuallyEdited(false)
                .volumeUtilization(new BigDecimal("0.8740")).weightUtilization(new BigDecimal("0.5000"))
                .frontAxleLoad(new BigDecimal("1200")).rearAxleLoad(new BigDecimal("1800"))
                .rehandlingCount(null).build();
        entityManager.persist(plan);
        return plan;
    }

    private CargoPackage cargo(Company company, String code, int weightKg) {
        packageSeq++;
        CargoPackage cargo = CargoPackage.builder().company(company).packageCode(code)
                .qrToken("lpt-qr-" + packageSeq).actualLength(new BigDecimal("50")).actualWidth(new BigDecimal("50"))
                .actualHeight(new BigDecimal("50")).actualWeightKg(BigDecimal.valueOf(weightKg)).build();
        entityManager.persist(cargo);
        return cargo;
    }

    private void place(LoadPlan plan, CargoPackage cargo, DeliveryStop stop, int x, int y, int z, int length,
                       int width, int height, int step, boolean pinned) {
        entityManager.persist(PackagePlacement.builder().loadPlan(plan).cargoPackage(cargo).stopZone(stop)
                .posX(x).posY(y).posZ(z).packedLength(length).packedWidth(width).packedHeight(height)
                .rotationType(0).stepSequence(step).pinned(pinned).build());
    }
}
