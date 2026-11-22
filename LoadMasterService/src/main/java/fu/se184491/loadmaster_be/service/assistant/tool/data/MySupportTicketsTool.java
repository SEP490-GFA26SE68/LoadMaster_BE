package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Yêu cầu hỗ trợ do chính người đang hỏi gửi — {@link SupportTicketService#getTickets} chỉ trả phiếu của người gửi với
 * mọi vai trò công ty. Chỉ năm vai trò công ty: nhân sự nền tảng đọc được mọi phiếu nên không có công cụ này. Không trả
 * mô tả, nội dung trao đổi, email hay mã số nội bộ.
 */
@Component
@RequiredArgsConstructor
public class MySupportTicketsTool implements AssistantTool {

    static final int LIMIT = 20;

    private final SupportTicketService tickets;

    /** Một phiếu, mới gửi gần nhất trước. Giờ là giờ Việt Nam. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ticket(String ticketCode, String subject, TicketCategory category, TicketStatus status,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.DISPATCHER, UserRole.WAREHOUSE_WORKER,
                UserRole.DRIVER);
    }

    @Tool(name = "get_my_support_tickets", description = """
            Các yêu cầu hỗ trợ do chính người đang hỏi gửi (tối đa 20, mới nhất trước) kèm tổng số: mã phiếu, tiêu \
            đề, loại, trạng thái (OPEN mở, IN_PROGRESS đang xử lý, RESOLVED đã giải quyết, CLOSED đã đóng), lúc gửi, \
            lần hoạt động gần nhất. Dùng khi người hỏi hỏi yêu cầu hỗ trợ của họ đã tới đâu.""")
    public ToolPage<Ticket> getMySupportTickets() {
        List<SupportTicketResponse> all = tickets.getTickets(null, null);
        List<Ticket> rows = all.stream().limit(LIMIT).map(MySupportTicketsTool::ticketOf).toList();
        return new ToolPage<>(all.size(), rows.size(), rows, false);
    }

    private static Ticket ticketOf(SupportTicketResponse ticket) {
        return new Ticket(ticket.getTicketCode(), ticket.getSubject(), ticket.getCategory(), ticket.getStatus(),
                ticket.getCreatedAt(), ticket.getUpdatedAt());
    }
}
