package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Đội xe qua đúng hàm của màn trên H2: xe của công ty người hỏi, xe đang chạy kèm chuyến, kích thước từ loại xe. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class FleetToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private FleetTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company longBinh = data.company("AST-FL-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-FL-PN", "Vận tải Phương Nam");
        User dispatcher = data.user(longBinh, "ast-fl-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        data.user(longBinh, "ast-fl-driver", UserRole.DRIVER, "Phạm Văn Tài");
        data.user(phuongNam, "ast-fl-pn", UserRole.DISPATCHER, "Lâm Viết");
        Vehicle busy = data.vehicle(longBinh, "51C-555.01");
        data.vehicle(longBinh, "51C-555.02");
        Vehicle repair = data.vehicle(longBinh, "51C-555.03");
        repair.setStatus(VehicleStatus.MAINTENANCE);
        repair.setMaintenanceNote("Thay lốp sau");
        repair.setMaintenanceSince(LocalDateTime.of(2026, 11, 23, 7, 0));
        data.vehicle(phuongNam, "61C-999.99");
        data.trip(longBinh, busy, dispatcher, null, "TRIP-AST-FL-1", "Biên Hoà — Dĩ An", TripStatus.IN_TRANSIT,
                LocalDateTime.of(2026, 11, 24, 8, 0));
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsOnlyTheCallersVehiclesWithTripMaintenanceAndBodySize() {
        ToolFixtures.signIn("ast-fl-dispatcher");

        String json = ToolResults.json(tool.listVehicles(null));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(3);
        assertThat(json).doesNotContain("61C-999.99").doesNotContain("\"id\"").doesNotContain("vehicleTypeId");
        JsonNode busy = row(out, "51C-555.01");
        assertThat(busy.get("status").asString()).isEqualTo("IN_TRIP");
        assertThat(busy.get("currentTripCode").asString()).isEqualTo("TRIP-AST-FL-1");
        assertThat(busy.get("currentTripStatus").asString()).isEqualTo("IN_TRANSIT");
        assertThat(busy.get("innerLengthCm").asInt()).isEqualTo(520);
        assertThat(busy.get("maxPayloadKg").asInt()).isEqualTo(3500);
        JsonNode repair = row(out, "51C-555.03");
        assertThat(repair.get("status").asString()).isEqualTo("MAINTENANCE");
        assertThat(repair.get("maintenanceNote").asString()).isEqualTo("Thay lốp sau");
        assertThat(row(out, "51C-555.02").has("currentTripCode")).isFalse();
    }

    @Test
    void filtersByStatusAndRejectsUnknownOnes() {
        ToolFixtures.signIn("ast-fl-dispatcher");

        JsonNode free = JSON.readTree(ToolResults.json(tool.listVehicles("available")));

        assertThat(free.get("total").asInt()).isEqualTo(1);
        assertThat(free.get("rows").get(0).get("licensePlate").asString()).isEqualTo("51C-555.02");
        assertThat(tool.listVehicles("BROKEN")).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
    }

    private static JsonNode row(JsonNode out, String plate) {
        for (JsonNode row : out.get("rows")) {
            if (plate.equals(row.get("licensePlate").asString())) {
                return row;
            }
        }
        throw new AssertionError("không thấy xe " + plate);
    }
}
