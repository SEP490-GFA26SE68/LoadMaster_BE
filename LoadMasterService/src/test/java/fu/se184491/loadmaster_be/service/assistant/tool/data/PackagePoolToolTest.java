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

/** Kho kiện trên H2: chỉ công ty của người hỏi, lọc trạng thái và cờ, 30 dòng kèm tổng thật, chuyến qua đơn. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class PackagePoolToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private PackagePoolTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company longBinh = data.company("AST-PP-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-PP-PN", "Vận tải Phương Nam");
        User dispatcher = data.user(longBinh, "ast-pp-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        data.user(longBinh, "ast-pp-worker", UserRole.WAREHOUSE_WORKER, "Đỗ Thị Mai");
        Trip trip = data.trip(longBinh, data.vehicle(longBinh, "51C-444.44"), dispatcher, null, "TRIP-AST-PP-1",
                "Biên Hoà — Dĩ An", TripStatus.PLANNED, LocalDateTime.of(2026, 11, 24, 8, 0));
        DeliveryStop stop = data.stop(trip, 3, "Điện máy Xanh Dĩ An");
        TransportOrder order = TransportOrder.builder().company(longBinh).deliveryStop(stop)
                .orderCode("ORD-AST-PP-1").status(OrderStatus.ASSIGNED).build();
        entityManager.persist(order);
        cargo(longBinh, "PK-AST-PP-A", order, PackageStatus.PLANNED, null);
        cargo(longBinh, "PK-AST-PP-B", null, PackageStatus.PENDING, PackageFlag.DAMAGED);
        for (int i = 0; i < 30; i++) {
            cargo(longBinh, "PK-AST-PP-%02d".formatted(i), null, PackageStatus.PENDING, null);
        }
        cargo(phuongNam, "PK-AST-PP-PN", null, PackageStatus.PENDING, PackageFlag.DAMAGED);
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsOnlyTheCallersCompanyWithThirtyRowsAndTheRealTotal() {
        ToolFixtures.signIn("ast-pp-worker");

        String json = ToolResults.json(tool.listPackages(null, null));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(32);
        assertThat(out.get("shown").asInt()).isEqualTo(ToolResults.LIST_ROWS);
        assertThat(json).doesNotContain("PK-AST-PP-PN").doesNotContain("Phương Nam");
    }

    @Test
    void filtersByStatusAndFlagIncludingNoFlag() {
        ToolFixtures.signIn("ast-pp-dispatcher");

        JsonNode planned = JSON.readTree(ToolResults.json(tool.listPackages("planned", null)));
        JsonNode damaged = JSON.readTree(ToolResults.json(tool.listPackages(null, "DAMAGED")));
        JsonNode clean = JSON.readTree(ToolResults.json(tool.listPackages("PENDING", "none")));

        assertThat(planned.get("total").asInt()).isEqualTo(1);
        JsonNode row = planned.get("rows").get(0);
        assertThat(row.get("packageCode").asString()).isEqualTo("PK-AST-PP-A");
        assertThat(row.get("tripCode").asString()).isEqualTo("TRIP-AST-PP-1");
        assertThat(row.get("stopNumber").asInt()).isEqualTo(3);
        assertThat(damaged.get("total").asInt()).as("the other company's damaged package stays hidden").isEqualTo(1);
        assertThat(damaged.get("rows").get(0).get("packageCode").asString()).isEqualTo("PK-AST-PP-B");
        assertThat(clean.get("total").asInt()).isEqualTo(30);
    }

    @Test
    void carriesNoInternalIdsQrTokensOrOrderCodes() {
        ToolFixtures.signIn("ast-pp-worker");

        String json = ToolResults.json(tool.listPackages("PLANNED", null));

        assertThat(json).doesNotContain("\"id\"").doesNotContain("qr-").doesNotContain("qrToken")
                .doesNotContain("ORD-AST-PP-1").doesNotContain("SHIP-");
    }

    @Test
    void wrongFiltersAreRejected() {
        ToolFixtures.signIn("ast-pp-worker");

        assertThat(tool.listPackages("LOST", null)).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
        assertThat(tool.listPackages(null, "BROKEN")).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
    }

    private void cargo(Company company, String code, TransportOrder order, PackageStatus status, PackageFlag flag) {
        CargoPackage cargo = CargoPackage.builder().company(company).packageCode(code)
                .qrToken("qr-" + code.toLowerCase()).trackingBarcode("SHIP-" + code).order(order).status(status)
                .flag(flag).packageName("Thùng tivi").destination("Dĩ An, Bình Dương")
                .actualLength(new BigDecimal("60")).actualWidth(new BigDecimal("40"))
                .actualHeight(new BigDecimal("40")).actualWeightKg(new BigDecimal("18")).build();
        entityManager.persist(cargo);
    }
}
