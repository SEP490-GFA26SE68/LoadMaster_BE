package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Việc kho đọc qua đúng service của màn Kho trên H2 (lọc công ty); nhóm việc và tiến độ kiểm bằng service giả. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class WarehouseTasksToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EntityManager entityManager;
    @Autowired private WarehouseTasksTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;
    /** Vị trí xe đọc một view chỉ có ở PostgreSQL; công cụ không trả vị trí xe. */
    @MockitoBean private VehiclePresenceStore presenceStore;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void workerOnlySeesTripsOfTheirCompanyWithAnApprovedPlan() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company longBinh = data.company("AST-WH-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-WH-PN", "Vận tải Phương Nam");
        User dispatcher = data.user(longBinh, "ast-wh-dispatcher", UserRole.DISPATCHER, "Lê Thu Trang");
        data.user(longBinh, "ast-wh-worker", UserRole.WAREHOUSE_WORKER, "Đỗ Thị Mai");
        User otherDispatcher = data.user(phuongNam, "ast-wh-dispatcher-pn", UserRole.DISPATCHER, "Võ Hải Đăng");
        Vehicle truck = data.vehicle(longBinh, "51C-111.11");
        Vehicle otherTruck = data.vehicle(phuongNam, "65C-222.22");
        LocalDateTime departure = LocalDateTime.of(2026, 11, 24, 8, 0);
        data.plan(data.trip(longBinh, truck, dispatcher, null, "TRIP-AST-WH-1", "Biên Hoà — Thủ Đức",
                TripStatus.PLANNED, departure), true, 12);
        data.plan(data.trip(longBinh, truck, dispatcher, null, "TRIP-AST-WH-2", "Chưa duyệt",
                TripStatus.PLANNED, departure), false, 8);
        data.plan(data.trip(phuongNam, otherTruck, otherDispatcher, null, "TRIP-AST-WH-PN", "Cần Thơ — Sóc Trăng",
                TripStatus.PLANNED, departure), true, 30);
        entityManager.flush();
        ToolFixtures.signIn("ast-wh-worker");

        String json = ToolResults.json(tool.listWarehouseTasks());
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(1);
        JsonNode task = out.get("rows").get(0);
        assertThat(task.get("tripCode").asString()).isEqualTo("TRIP-AST-WH-1");
        assertThat(task.get("stage").asString()).isEqualTo("WAITING_STAGING");
        assertThat(task.get("totalPackages").asInt()).isEqualTo(12);
        assertThat(json).doesNotContain("TRIP-AST-WH-PN").doesNotContain("TRIP-AST-WH-2")
                .doesNotContain("tripId").doesNotContain("loadPlanId").doesNotContain("executionId")
                .doesNotContain("vehicleId").doesNotContain("Thủ Đức");
    }

    @Test
    void tasksAreGroupedByStageAndOnlyLoadingTripsCarryProgress() {
        WarehouseService warehouse = mock(WarehouseService.class);
        when(warehouse.getTasks(null, null, true)).thenReturn(List.of(
                task(1L, "TRIP-A", "PLANNED", null, null),
                WarehouseTaskResponse.builder().tripId(2L).tripCode("TRIP-B").status("PLANNED").awaitingReplan(true)
                        .replanReason("DAMAGED_PACKAGE").build(),
                task(3L, "TRIP-C", "LOADING", 30L, "COMPLETED"),
                task(4L, "TRIP-D", "LOADING", 40L, "IN_PROGRESS")));
        when(warehouse.getSession(4L)).thenReturn(StartLoadingResponse.builder().stagingRequired(true)
                .stagingComplete(false).stagedCount(5).confirmedCount(0).pendingCount(12).openShortageCount(1)
                .damagedCount(0).pendingManualCount(2).placements(List.of()).build());
        when(warehouse.getSession(3L)).thenThrow(new AppException(ErrorCode.TRIP_NOT_LOADING));

        JsonNode rows = JSON.readTree(ToolResults.json(new WarehouseTasksTool(warehouse).listWarehouseTasks()))
                .get("rows");

        assertThat(rows).extracting(row -> row.get("tripCode").asString())
                .containsExactly("TRIP-A", "TRIP-D", "TRIP-C", "TRIP-B");
        assertThat(rows).extracting(row -> row.get("stage").asString())
                .containsExactly("WAITING_STAGING", "LOADING", "LOADED", "AWAITING_REPLAN");
        JsonNode loading = rows.get(1).get("progress");
        assertThat(loading.get("stagedCount").asInt()).isEqualTo(5);
        assertThat(loading.get("remainingCount").asInt()).isEqualTo(12);
        assertThat(loading.get("openShortageCount").asInt()).isEqualTo(1);
        assertThat(loading.get("pendingManualCount").asInt()).isEqualTo(2);
        assertThat(rows.get(0).has("progress")).isFalse();
        assertThat(rows.get(2).has("progress")).as("phiên đổi giữa hai lần đọc: vẫn liệt kê").isFalse();
        assertThat(rows.get(3).get("replanReason").asString()).isEqualTo("DAMAGED_PACKAGE");
        assertThat(rows.toString()).doesNotContain("placements");
    }

    @Test
    void atMostTenLoadingSessionsAreReadPerCall() {
        WarehouseService warehouse = mock(WarehouseService.class);
        List<WarehouseTaskResponse> tasks = new ArrayList<>();
        IntStream.rangeClosed(1, 15).forEach(i -> tasks.add(task((long) i, "TRIP-L" + i, "LOADING", (long) i,
                "IN_PROGRESS")));
        when(warehouse.getTasks(null, null, true)).thenReturn(tasks);
        when(warehouse.getSession(anyLong())).thenReturn(StartLoadingResponse.builder().confirmedCount(1).build());

        new WarehouseTasksTool(warehouse).listWarehouseTasks();

        verify(warehouse, times(WarehouseTasksTool.MAX_SESSIONS)).getSession(anyLong());
    }

    private static WarehouseTaskResponse task(Long tripId, String code, String status, Long executionId,
                                              String executionStatus) {
        return WarehouseTaskResponse.builder().tripId(tripId).tripCode(code).status(status).executionId(executionId)
                .executionStatus(executionStatus).vehicleInfo("Xe tải 3,5 tấn - 51C-111.11").totalPackages(12)
                .loadPlanId(900L + tripId).build();
    }
}
