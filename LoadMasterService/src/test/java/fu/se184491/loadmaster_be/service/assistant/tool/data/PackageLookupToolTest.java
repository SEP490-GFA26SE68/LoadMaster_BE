package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Tra cứu kiện qua đúng service của màn Tra cứu kiện trên H2: ba lối mã, chuyến qua đơn, không lộ công ty khác. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class PackageLookupToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private PackageLookupTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    private ToolFixtures data;
    private Company longBinh;

    @BeforeEach
    void seed() {
        data = new ToolFixtures(entityManager);
        longBinh = data.company("AST-PK-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-PK-PN", "Vận tải Phương Nam");
        User dispatcher = data.user(longBinh, "ast-pk-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        data.user(longBinh, "ast-pk-worker", UserRole.WAREHOUSE_WORKER, "Đỗ Thị Mai");
        Trip trip = data.trip(longBinh, data.vehicle(longBinh, "51C-333.33"), dispatcher, null, "TRIP-AST-PK-1",
                "Biên Hoà — Dĩ An", TripStatus.LOADING, LocalDateTime.of(2026, 11, 24, 8, 0));
        DeliveryStop stop = data.stop(trip, 2, "Điện máy Xanh Dĩ An");
        TransportOrder order = TransportOrder.builder().company(longBinh).deliveryStop(stop)
                .orderCode("ORD-AST-PK-1").status(OrderStatus.ASSIGNED).build();
        entityManager.persist(order);
        cargo(longBinh, "PK-AST-01", "ast-qr-pk-01", "SHIP-778899", order, PackageStatus.LOADED, null);
        cargo(longBinh, "PK-AST-02", "ast-qr-pk-02", null, null, PackageStatus.PENDING, PackageFlag.NOT_FOUND);
        cargo(phuongNam, "PK-AST-PN", "ast-qr-pk-pn", "SHIP-PN-1", null, PackageStatus.PENDING, null);
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findsAPackageByCodeIgnoringCaseWithItsTripAndStop() {
        ToolFixtures.signIn("ast-pk-worker");

        JsonNode out = JSON.readTree(ToolResults.json(tool.lookupPackage(" pk-ast-01 ")));

        assertThat(out.get("total").asInt()).isEqualTo(1);
        JsonNode match = out.get("packages").get(0);
        assertThat(match.get("packageCode").asString()).isEqualTo("PK-AST-01");
        assertThat(match.get("status").asString()).isEqualTo("LOADED");
        assertThat(match.get("tripCode").asString()).isEqualTo("TRIP-AST-PK-1");
        assertThat(match.get("stopNumber").asInt()).isEqualTo(2);
        assertThat(match.get("stopName").asString()).isEqualTo("Điện máy Xanh Dĩ An");
    }

    @Test
    void findsAPackageByQrTokenInAnyCaseAndBySenderBarcode() {
        ToolFixtures.signIn("ast-pk-dispatcher");

        JsonNode byQr = JSON.readTree(ToolResults.json(tool.lookupPackage("AST-QR-PK-02")));
        JsonNode byBarcode = JSON.readTree(ToolResults.json(tool.lookupPackage("ship-778899")));

        assertThat(byQr.get("total").asInt()).isEqualTo(1);
        assertThat(byQr.get("packages").get(0).get("packageCode").asString()).isEqualTo("PK-AST-02");
        assertThat(byQr.get("packages").get(0).get("flag").asString()).isEqualTo("NOT_FOUND");
        assertThat(byQr.get("packages").get(0).has("tripCode")).isFalse();
        assertThat(byBarcode.get("total").asInt()).isEqualTo(1);
        assertThat(byBarcode.get("packages").get(0).get("packageCode").asString()).isEqualTo("PK-AST-01");
    }

    @Test
    void packagesOfAnotherCompanyAreNotFoundByAnyCode() {
        ToolFixtures.signIn("ast-pk-worker");

        for (String code : new String[]{"PK-AST-PN", "ast-qr-pk-pn", "SHIP-PN-1"}) {
            String json = ToolResults.json(tool.lookupPackage(code));
            assertThat(JSON.readTree(json).get("total").asInt()).as(code).isZero();
            assertThat(json).as(code).doesNotContain("Phương Nam");
        }
    }

    @Test
    void outputCarriesNoInternalIdsOrQrTokens() {
        ToolFixtures.signIn("ast-pk-worker");

        String json = ToolResults.json(tool.lookupPackage("PK-AST-01"));

        assertThat(json).doesNotContain("ast-qr-pk-01").doesNotContain("\"id\"").doesNotContain("qrToken")
                .doesNotContain("ORD-AST-PK-1");
    }

    @Test
    void blankOrOversizedCodesFindNothing() {
        ToolFixtures.signIn("ast-pk-worker");

        assertThat(tool.lookupPackage("   ").total()).isZero();
        assertThat(tool.lookupPackage(null).total()).isZero();
        assertThat(tool.lookupPackage("X".repeat(101)).total()).isZero();
    }

    private void cargo(Company company, String code, String qrToken, String barcode, TransportOrder order,
                       PackageStatus status, PackageFlag flag) {
        CargoPackage cargo = CargoPackage.builder().company(company).packageCode(code).qrToken(qrToken)
                .trackingBarcode(barcode).order(order).status(status).flag(flag).packageName("Thùng tivi")
                .destination("Dĩ An, Bình Dương").actualLength(new BigDecimal("60")).actualWidth(new BigDecimal("40"))
                .actualHeight(new BigDecimal("40")).actualWeightKg(new BigDecimal("18")).build();
        entityManager.persist(cargo);
    }
}
