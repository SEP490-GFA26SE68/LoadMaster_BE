package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Việc của kho đang có, đọc qua đúng hàm của {@code GET /api/warehouse/tasks?includeAwaitingReplan=true} (màn Kho của
 * web và app): service tự lấy công ty của người đang hỏi. Chuyến Đang xếp hàng thêm tiến độ từ phiên xếp
 * ({@code GET /api/warehouse/tasks/{tripId}}). Không trả mã số nội bộ, không trả tuyến hay điểm giao.
 */
@Component
@RequiredArgsConstructor
public class WarehouseTasksTool implements AssistantTool {

    /** Số phiên xếp đọc thêm tiến độ trong một lần gọi; chuyến đang xếp quá số này chỉ có trạng thái. */
    static final int MAX_SESSIONS = 10;

    private final WarehouseService warehouse;

    /**
     * Nhóm việc: {@code WAITING_STAGING} chờ soạn (đã có phương án duyệt, chưa bắt đầu); {@code LOADING} đang soạn /
     * xếp; {@code LOADED} xếp xong, chờ tài xế xuất phát; {@code AWAITING_REPLAN} chờ điều phối viên tối ưu lại vì
     * kiện thiếu hoặc hỏng.
     */
    public enum Stage { WAITING_STAGING, LOADING, LOADED, AWAITING_REPLAN }

    /** Một việc của kho; số kiện là của phương án đã duyệt, tiến độ chỉ có ở chuyến đang xếp. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String tripCode, Stage stage, String vehicle, LocalDateTime departureTime,
                       Integer totalPackages, String replanReason, Progress progress) {
    }

    /** Tiến độ phiên xếp: đã soạn, đã xếp (đã đối chiếu), còn chờ, kiện báo thiếu chưa quyết, kiện hỏng, xác nhận tay chờ duyệt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(Boolean stagingRequired, Boolean stagingComplete, Integer stagedCount,
                           Integer loadedCount, Integer remainingCount, Integer openShortageCount,
                           Integer damagedCount, Integer pendingManualCount) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.WAREHOUSE_WORKER);
    }

    @Tool(name = "list_warehouse_tasks", description = """
            Việc của kho trong công ty của nhân viên kho đang hỏi, như màn Kho: chuyến chờ soạn (WAITING_STAGING), \
            đang soạn / xếp (LOADING) kèm tiến độ — đã soạn, đã xếp, còn lại, kiện báo thiếu chưa quyết, kiện hỏng, \
            xác nhận tay chờ điều phối viên duyệt —, xếp xong chờ xuất phát (LOADED) và chờ điều phối viên tối ưu \
            lại (AWAITING_REPLAN; replanReason STAGING_SHORTAGE thiếu kiện, DAMAGED_PACKAGE kiện hỏng). Chỉ có mã \
            chuyến, xe, giờ xuất phát và số kiện; không có tuyến hay điểm giao. Dùng khi nhân viên kho hỏi hôm nay \
            có việc gì, chuyến nào cần soạn hay xếp, hoặc đã xếp tới đâu.""")
    public ToolPage<Task> listWarehouseTasks() {
        List<WarehouseTaskResponse> tasks = warehouse.getTasks(null, null, true).stream()
                .sorted(Comparator.comparing((WarehouseTaskResponse task) -> stageOf(task).ordinal())
                        .thenComparing(WarehouseTaskResponse::getDepartureTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int[] sessions = {0};
        List<Task> rows = tasks.stream().map(task -> taskOf(task, sessions)).toList();
        return ToolResults.page(rows, ToolResults.MAX_ROWS);
    }

    private Task taskOf(WarehouseTaskResponse task, int[] sessions) {
        Stage stage = stageOf(task);
        Progress progress = null;
        if ((stage == Stage.LOADING || stage == Stage.LOADED) && task.getTripId() != null
                && sessions[0] < MAX_SESSIONS) {
            sessions[0]++;
            progress = progressOf(task.getTripId());
        }
        return new Task(task.getTripCode(), stage, task.getVehicleInfo(), task.getDepartureTime(),
                task.getTotalPackages(), stage == Stage.AWAITING_REPLAN ? task.getReplanReason() : null, progress);
    }

    private Progress progressOf(Long tripId) {
        try {
            StartLoadingResponse session = warehouse.getSession(tripId);
            return new Progress(session.getStagingRequired(), session.getStagingComplete(),
                    session.getStagedCount(), session.getConfirmedCount(), session.getPendingCount(),
                    session.getOpenShortageCount(), session.getDamagedCount(), session.getPendingManualCount());
        } catch (AppException e) {
            // Phiên vừa đổi giữa hai lần đọc: vẫn liệt kê chuyến, chỉ không có tiến độ.
            return null;
        }
    }

    static Stage stageOf(WarehouseTaskResponse task) {
        if (Boolean.TRUE.equals(task.getAwaitingReplan())) {
            return Stage.AWAITING_REPLAN;
        }
        if ("LOADING".equals(task.getStatus())) {
            return "COMPLETED".equals(task.getExecutionStatus()) ? Stage.LOADED : Stage.LOADING;
        }
        return Stage.WAITING_STAGING;
    }
}
