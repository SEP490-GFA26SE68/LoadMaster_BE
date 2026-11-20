package fu.se184491.loadmaster_be.service.assistant.tool.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator.ViewerPlan;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Lệnh cho khung 3D đang mở (đặc tả mục 8): chọn kiện, góc nhìn, bước xếp, chế độ màu, tab của hộp thông tin. Chỉ có khi
 * lượt hỏi từ Planner có mã phương án ({@link #needsViewer()}). Công cụ không đổi dữ liệu: nó thêm một lệnh vào câu trả
 * lời sau khi kiểm bằng {@link UiActionValidator} trên phương án đang xem; dịch vụ lọc lần cuối, FE kiểm lần nữa và bỏ
 * qua mọi lệnh khi Planner đang ở chế độ Chỉnh sửa. Không có lệnh dời, xoay, ghim hay duyệt.
 */
@Component
@RequiredArgsConstructor
public class ViewerActionTools implements AssistantTool {

    public static final String ADDED = "ADDED";
    public static final String INVALID = "INVALID";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String LIMIT_REACHED = "LIMIT_REACHED";

    private final LoadPlanQueryService loadPlans;

    /**
     * {@code ADDED}: lệnh sẽ chạy trên khung 3D của người hỏi. {@code INVALID} kèm {@code reason}: tham số không hợp lệ,
     * không có lệnh nào. {@code NOT_AVAILABLE}: không đọc được phương án đang xem. {@code LIMIT_REACHED}: đã đủ 5 lệnh.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String status, String reason) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Override
    public boolean needsViewer() {
        return true;
    }

    @Tool(name = "select_package", description = """
            Chọn (làm nổi) một kiện đã xếp trên khung 3D người dùng đang mở. Chỉ kiện có trong phương án đang xem \
            (INVALID PACKAGE_NOT_IN_PLAN nếu không có hoặc kiện chưa xếp). Không dời, xoay, ghim hay duyệt.""")
    public Result selectPackage(@ToolParam(description = "Mã kiện như trên nhãn, lấy từ công cụ hoặc ngữ cảnh lượt")
                                String packageCode, ToolContext context) {
        return add(context, new UiAction(UiActionValidator.SELECT_PACKAGE, packageCode, null, null, null, null));
    }

    @Tool(name = "set_camera_preset", description = """
            Đổi góc nhìn của khung 3D đang mở. Chỉ một trong: truoc (nhìn từ đầu xe), cua-sau (nhìn từ cửa sau), \
            ben-hong (nhìn bên hông), tren (nhìn từ trên xuống), goc-cheo (góc chéo), gam-xe (nhìn từ dưới gầm).""")
    public Result setCameraPreset(@ToolParam(description = "truoc, cua-sau, ben-hong, tren, goc-cheo hoặc gam-xe")
                                  String preset, ToolContext context) {
        return add(context, new UiAction(UiActionValidator.SET_CAMERA_PRESET, null, preset, null, null, null));
    }

    @Tool(name = "set_step", description = """
            Tua khung 3D tới một bước xếp: hiện các kiện đã lên xe tới bước đó (0 là thùng rỗng, tối đa là số bước \
            của phương án). Bước của một kiện là loadingStep của get_package_facts.""")
    public Result setStep(@ToolParam(description = "Số bước, từ 0 tới số bước của phương án") Integer step,
                          ToolContext context) {
        return add(context, new UiAction(UiActionValidator.SET_STEP, null, null, step, null, null));
    }

    @Tool(name = "set_color_mode", description = """
            Đổi cách tô màu kiện trên khung 3D: diem-giao (theo điểm giao), kien-goc (theo kiện gốc), khoi-luong \
            (theo khối lượng).""")
    public Result setColorMode(@ToolParam(description = "diem-giao, kien-goc hoặc khoi-luong") String mode,
                               ToolContext context) {
        return add(context, new UiAction(UiActionValidator.SET_COLOR_MODE, null, null, null, mode, null));
    }

    @Tool(name = "open_tab", description = """
            Mở một tab của danh sách kiện cạnh khung 3D: unplaced (kiện chưa xếp), pinned (kiện đã ghim), placed \
            (kiện đã xếp).""")
    public Result openTab(@ToolParam(description = "unplaced, pinned hoặc placed") String tab, ToolContext context) {
        return add(context, new UiAction(UiActionValidator.OPEN_TAB, null, null, null, null, tab));
    }

    private Result add(ToolContext context, UiAction asked) {
        AssistantTurn turn = AssistantTurn.of(context);
        Optional<ViewerPlan> plan = turn.viewerPlan(() -> load(turn.viewerPlanId()));
        if (plan.isEmpty()) {
            return new Result(NOT_AVAILABLE, null);
        }
        UiActionValidator.Check check = UiActionValidator.check(asked, plan.get());
        if (!check.ok()) {
            return new Result(INVALID, check.reason());
        }
        return turn.addUiAction(check.action()) ? new Result(ADDED, null) : new Result(LIMIT_REACHED, null);
    }

    /** Phương án đang xem của công ty người hỏi ({@code getPlan} tự lọc công ty); đọc không được thì trống. */
    private Optional<ViewerPlan> load(Long loadPlanId) {
        if (loadPlanId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ViewerPlan.of(loadPlans.getPlan(loadPlanId)));
        } catch (AppException e) {
            return Optional.empty();
        }
    }
}
