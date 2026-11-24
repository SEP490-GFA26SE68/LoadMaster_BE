package fu.se184491.loadmaster_be.service.assistant.tool.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.output.LinkCatalog;
import fu.se184491.loadmaster_be.service.assistant.output.LinkTarget;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Gắn liên kết tới một màn vào câu trả lời. Mô hình chỉ chọn đích trong {@link LinkTarget}; đường dẫn và nhãn do
 * {@link LinkCatalog} dựng theo vai trò của người hỏi, nên mô hình không bao giờ tự viết URL. Mọi người hỏi, kể cả khách.
 */
@Component
public class LinkTool implements AssistantTool {

    public static final String NAME = "suggest_link";

    /** {@code ADDED} kèm nhãn hiện dưới câu trả lời; {@code NOT_AVAILABLE}; {@code LIMIT_REACHED}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String status, String label) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.allOf(UserRole.class);
    }

    @Override
    public boolean guest() {
        return true;
    }

    @Tool(name = NAME, description = """
            Gắn một liên kết tới màn của ứng dụng (hoặc bảng giá, trang đăng ký) dưới câu trả lời; hệ thống dựng \
            đường dẫn và nhãn. Dùng khi người dùng cần mở một màn để xem hay làm việc. Không bao giờ tự viết URL \
            hay đường dẫn trong câu trả lời. ADDED: liên kết đã gắn, chỉ cần nhắc tên màn. NOT_AVAILABLE: người hỏi \
            không mở được màn đó (hoặc thiếu mã chuyến), đừng nhắc tới liên kết. Tối đa 3 liên kết mỗi câu trả lời \
            (LIMIT_REACHED).""")
    public Result suggestLink(
            @ToolParam(description = """
                    Màn cần mở: PRICING bảng giá; TRIAL_SIGNUP đăng ký dùng thử (khách chưa có tài khoản); TRIPS \
                    danh sách chuyến; TRIP_DETAIL chi tiết một chuyến; OPTIMIZATION_SETUP thiết lập tối ưu xếp hàng \
                    của một chuyến; PLANNER phương án xếp hàng 3D của một chuyến; COMPARE so sánh ba phương án của \
                    một chuyến; MONITORING giám sát xe đang chạy; REQUIREMENTS yêu cầu giao; PACKAGES kho kiện; \
                    BILLING gói và credit của công ty; PAYROLL bảng lương của công ty (quản lý tính và chốt); \
                    PAYSLIP phiếu lương của chính người hỏi (điều phối viên, nhân viên kho); PROFILE hồ sơ cá nhân; \
                    DRIVER_TRIPS chuyến của tài xế; WAREHOUSE màn làm việc của nhân viên kho; PACKAGE_LOOKUP tra \
                    cứu kiện theo mã; TRIP_CLAIMS phiếu tài xế xin nhận chuyến (điều phối viên duyệt); FLEET đội \
                    xe; SUPPORT_DESK yêu cầu hỗ trợ của mọi công ty (hỗ trợ khách hàng); GUEST_CONTACTS liên hệ của \
                    khách từ trang giới thiệu; COMPANIES danh sách công ty của nền tảng; TRIAL_REQUESTS đơn đăng ký \
                    dùng thử chờ duyệt""")
            LinkTarget target,
            @ToolParam(required = false, description = """
                    Mã chuyến dạng TRIP-…, bắt buộc với TRIP_DETAIL, OPTIMIZATION_SETUP, PLANNER, COMPARE; lấy từ \
                    kết quả công cụ hoặc ngữ cảnh lượt, không tự đặt""")
            String tripCode,
            ToolContext context) {
        AssistantTurn turn = AssistantTurn.of(context);
        Optional<AssistantLink> link = LinkCatalog.resolve(target, tripCode, turn.scope(), turn.locale());
        if (link.isEmpty()) {
            return new Result("NOT_AVAILABLE", null);
        }
        return turn.addLink(link.get()) ? new Result("ADDED", link.get().label()) : new Result("LIMIT_REACHED", null);
    }
}
