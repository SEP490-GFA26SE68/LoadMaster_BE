package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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

/** Chuyến đang mở đọc qua đúng service của tab "Chuyến đang mở" trên H2: chỉ chuyến chưa có tài xế của công ty mình. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class OpenTripsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private OpenTripsTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void driverSeesOnlyOpenTripsOfTheirCompanyWithoutInternalIds() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company longBinh = data.company("AST-OT-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-OT-PN", "Vận tải Phương Nam");
        User dispatcher = data.user(longBinh, "ast-ot-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        User me = data.user(longBinh, "ast-ot-driver", UserRole.DRIVER, "Trần Minh Khoa");
        User colleague = data.user(longBinh, "ast-ot-driver-2", UserRole.DRIVER, "Phạm Văn Tuấn");
        User otherDispatcher = data.user(phuongNam, "ast-ot-dispatcher-pn", UserRole.DISPATCHER, "Võ Hải Đăng");
        Vehicle truck = data.vehicle(longBinh, "51C-444.44");
        Vehicle otherTruck = data.vehicle(phuongNam, "65C-555.55");
        LocalDateTime morning = LocalDateTime.of(2026, 11, 25, 7, 0);
        Trip open = data.trip(longBinh, truck, dispatcher, null, "TRIP-AST-OT-1", "Biên Hoà — Thủ Đức",
                TripStatus.PLANNED, morning.plusHours(2));
        data.stop(open, 1, "Siêu thị Thủ Đức");
        data.stop(open, 2, "Kho Linh Trung");
        data.trip(longBinh, truck, dispatcher, null, "TRIP-AST-OT-2", "Nháp sớm", TripStatus.DRAFT, morning);
        data.trip(longBinh, truck, dispatcher, colleague, "TRIP-AST-OT-TAKEN", "Đã có tài xế", TripStatus.PLANNED,
                morning);
        data.trip(longBinh, truck, dispatcher, null, "TRIP-AST-OT-RUN", "Đang chạy", TripStatus.IN_TRANSIT, morning);
        data.trip(phuongNam, otherTruck, otherDispatcher, null, "TRIP-AST-OT-PN", "Cần Thơ — Sóc Trăng",
                TripStatus.PLANNED, morning);
        entityManager.flush();
        ToolFixtures.signIn(me.getKeycloakId());

        String json = ToolResults.json(tool.listOpenTrips());
        JsonNode rows = JSON.readTree(json).get("rows");

        assertThat(rows).extracting(row -> row.get("tripCode").asString())
                .containsExactly("TRIP-AST-OT-2", "TRIP-AST-OT-1");
        assertThat(rows.get(1).get("stopCount").asInt()).isEqualTo(2);
        assertThat(rows.get(1).has("myClaimStatus")).isFalse();
        assertThat(json).doesNotContain("TRIP-AST-OT-PN").doesNotContain("TAKEN").doesNotContain("TRIP-AST-OT-RUN")
                .doesNotContain("\"id\"").doesNotContain("tripId").doesNotContain("Phạm Văn Tuấn");
    }
}
