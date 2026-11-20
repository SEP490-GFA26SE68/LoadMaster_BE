package fu.se184491.loadmaster_be.service.assistant.tool.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chuyển câu hỏi cho đội hỗ trợ bằng một yêu cầu hỗ trợ của chính người hỏi. Chặt hơn đặc tả một bước: phiếu chỉ được tạo
 * khi tin trợ lý liền trước đã mời chuyển ({@code handoff_offered}) — người dùng nói thẳng "cho tôi gặp nhân viên" cũng
 * qua một câu hỏi lại. Mỗi hội thoại một phiếu: mã phiếu được ghi vào hội thoại trong cùng giao dịch với phiếu, hội thoại
 * đã có mã thì phiếu mới bị huỷ.
 */
@Component
public class HandoffTool implements AssistantTool {

    static final int SUBJECT_MAX = 120;
    static final int RECENT_MESSAGES = 10;
    static final int LINE_MAX = 1000;
    private static final Set<TicketCategory> CATEGORIES =
            EnumSet.of(TicketCategory.BILLING, TicketCategory.ALGORITHM_ERROR, TicketCategory.SYSTEM_BUG);

    private final SupportTicketService tickets;
    private final AssistantConversationRepository conversations;
    private final TransactionOperations transactions;

    @Autowired
    public HandoffTool(SupportTicketService tickets, AssistantConversationRepository conversations,
                       PlatformTransactionManager transactionManager) {
        this(tickets, conversations, new TransactionTemplate(transactionManager));
    }

    HandoffTool(SupportTicketService tickets, AssistantConversationRepository conversations,
                TransactionOperations transactions) {
        this.tickets = tickets;
        this.conversations = conversations;
        this.transactions = transactions;
    }

    /** {@code NEED_CONFIRMATION}; {@code CREATED} hoặc {@code ALREADY_CREATED} kèm mã yêu cầu hỗ trợ. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String status, String ticketCode) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.DISPATCHER, UserRole.WAREHOUSE_WORKER,
                UserRole.DRIVER);
    }

    @Tool(name = "request_human_support", description = """
            Chuyển câu hỏi cho nhân viên hỗ trợ của LoadMaster bằng một yêu cầu hỗ trợ. Gọi trước khi viết câu trả lời \
            mỗi khi câu hỏi về LoadMaster mà bạn không trả lời được trọn vẹn (kể cả kế hoạch, thời điểm ra tính năng \
            hay phiên bản mới), hoặc người dùng muốn gặp nhân viên — kể cả khi \
            định hỏi họ có muốn chuyển không: nút chuyển chỉ hiện khi công cụ này được gọi. Lần gọi đầu không tạo gì. \
            NEED_CONFIRMATION: chưa chuyển; hỏi người dùng có muốn chuyển cho nhân viên hỗ trợ không, chỉ khi họ đồng \
            ý ở tin tiếp theo mới gọi lại. CREATED: đã tạo yêu cầu, báo \
            mã cho người dùng; họ theo dõi ở mục "Yêu cầu hỗ trợ" của menu tài khoản. ALREADY_CREATED: hội thoại này \
            đã có yêu cầu, báo lại mã đó. Không hứa thời gian phản hồi.""")
    public Result requestHumanSupport(
            @ToolParam(description = "Tóm tắt vấn đề của người dùng trong một hai câu, bằng ngôn ngữ của họ")
            String summary,
            @ToolParam(description = """
                    BILLING (gói, credit, thanh toán), ALGORITHM_ERROR (kết quả tối ưu xếp hàng), SYSTEM_BUG \
                    (lỗi hay việc khác)""")
            String category,
            ToolContext context) {
        AssistantTurn turn = AssistantTurn.of(context);
        if (turn.ticketCode() != null) {
            turn.reportTicket(turn.ticketCode());
            return new Result("ALREADY_CREATED", turn.ticketCode());
        }
        if (!turn.handoffInvited() || turn.conversationId() == null) {
            turn.offerHandoff();
            return new Result("NEED_CONFIRMATION", null);
        }
        String text = summary == null ? "" : summary.strip();
        if (text.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        CreateSupportTicketRequest request = new CreateSupportTicketRequest(cut(text, SUBJECT_MAX),
                description(text, turn.recent()), categoryOf(category), null);
        String created = transactions.execute(status -> {
            String code = tickets.createTicket(request).getTicketCode();
            if (conversations.claimHandoffTicket(turn.conversationId(), code) == 1) {
                return code;
            }
            // Một lượt khác của cùng hội thoại vừa tạo phiếu: huỷ phiếu này
            status.setRollbackOnly();
            return null;
        });
        if (created != null) {
            turn.reportTicket(created);
            return new Result("CREATED", created);
        }
        String existing = conversations.findById(turn.conversationId())
                .map(AssistantConversation::getHandoffTicketCode).orElse(null);
        turn.reportTicket(existing);
        return new Result("ALREADY_CREATED", existing);
    }

    /** Ba loại người dùng được chọn; loại khác (kể cả mở khoá tài khoản dùng thử) hay sai chữ là lỗi chung. */
    static TicketCategory categoryOf(String value) {
        if (value != null) {
            try {
                TicketCategory category = TicketCategory.valueOf(value.strip().toUpperCase(Locale.ROOT));
                if (CATEGORIES.contains(category)) {
                    return category;
                }
            } catch (IllegalArgumentException ignored) {
                // Loại do mô hình viết sai: về lỗi chung
            }
        }
        return TicketCategory.SYSTEM_BUG;
    }

    /** Tóm tắt rồi tối đa {@value #RECENT_MESSAGES} tin gần nhất, cũ trước, mỗi tin cắt {@value #LINE_MAX} ký tự. */
    private static String description(String summary, List<AssistantTurn.Line> recent) {
        StringBuilder out = new StringBuilder(summary).append("\n\n--- Trao đổi với trợ lý Lumo ---");
        List<AssistantTurn.Line> lines = recent.subList(Math.max(0, recent.size() - RECENT_MESSAGES), recent.size());
        for (AssistantTurn.Line line : lines) {
            out.append('\n').append(line.fromUser() ? "Người dùng: " : "Lumo: ").append(cut(line.text(), LINE_MAX));
        }
        return out.toString();
    }

    /** Cắt theo ký tự, không tách đôi cặp UTF-16. */
    private static String cut(String text, int max) {
        String value = text == null ? "" : text.strip();
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max)).strip();
    }
}
