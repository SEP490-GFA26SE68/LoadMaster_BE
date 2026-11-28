package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator.ViewerPlan;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Trạng thái của một lượt hỏi mà công cụ hành động đọc và ghi: phạm vi, ngôn ngữ, liên kết đã gắn, việc chuyển nhân
 * viên, lệnh cho khung 3D và phương án đang xem. Sổ công cụ đưa nó tới công cụ qua {@link ToolContext} ({@link #CONTEXT_KEY}); dịch vụ đọc lại sau vòng gọi để
 * dựng câu trả lời và lưu tin. Một lượt chạy trên một luồng nên không cần đồng bộ.
 */
public final class AssistantTurn {

    public static final String CONTEXT_KEY = "assistantTurn";
    public static final int MAX_LINKS = 3;

    private final ToolScope scope;
    private final String locale;
    private final Long conversationId;
    private final boolean handoffInvited;
    private final List<Line> recent;
    private final Map<String, AssistantLink> links = new LinkedHashMap<>();
    private final Long viewerPlanId;
    private final List<UiAction> uiActions = new ArrayList<>();
    /** {@code null}: chưa công cụ nào đọc phương án đang xem trong lượt này. */
    private Optional<ViewerPlan> viewerPlan;
    private String ticketCode;
    private boolean ticketReported;
    private boolean handoffOffered;

    /** Một tin của hội thoại, cũ trước; tin cuối là câu hỏi của lượt này. */
    public record Line(boolean fromUser, String text) {
    }

    /**
     * @param conversationId hội thoại đã lưu, {@code null} khi lượt này mở hội thoại mới
     * @param handoffInvited tin trợ lý liền trước đã mời chuyển nhân viên ({@code handoff_offered})
     * @param ticketCode     yêu cầu hỗ trợ hội thoại đã có, hoặc {@code null}
     */
    public AssistantTurn(ToolScope scope, String locale, Long conversationId, boolean handoffInvited,
                         String ticketCode, List<Line> recent) {
        this(scope, locale, conversationId, handoffInvited, ticketCode, recent, null);
    }

    /** @param viewerPlanId phương án đang mở ở khung 3D (chỉ khi {@code scope.viewer()}), hoặc {@code null} */
    public AssistantTurn(ToolScope scope, String locale, Long conversationId, boolean handoffInvited,
                         String ticketCode, List<Line> recent, Long viewerPlanId) {
        this.scope = scope;
        this.locale = locale;
        this.conversationId = conversationId;
        this.handoffInvited = handoffInvited;
        this.ticketCode = ticketCode;
        this.recent = recent == null ? List.of() : List.copyOf(recent);
        this.viewerPlanId = scope != null && scope.viewer() ? viewerPlanId : null;
    }

    /** Lượt của một lần gọi công cụ; thiếu là lỗi lập trình (sổ công cụ luôn gắn lượt khi gọi qua {@link AssistantToolRegistry#toolsFor(AssistantTurn, ToolCallLog)}). */
    public static AssistantTurn of(ToolContext context) {
        Object turn = context == null ? null : context.getContext().get(CONTEXT_KEY);
        if (turn instanceof AssistantTurn found) {
            return found;
        }
        throw new IllegalStateException("Công cụ hành động được gọi ngoài một lượt hỏi");
    }

    public ToolScope scope() {
        return scope;
    }

    public String locale() {
        return locale;
    }

    public Long conversationId() {
        return conversationId;
    }

    public boolean handoffInvited() {
        return handoffInvited;
    }

    public List<Line> recent() {
        return recent;
    }

    /** Gắn một liên kết; trùng đích thì tính một lần. {@code false} khi đã đủ {@value #MAX_LINKS} liên kết khác. */
    public boolean addLink(AssistantLink link) {
        if (links.containsKey(link.to())) {
            return true;
        }
        if (links.size() >= MAX_LINKS) {
            return false;
        }
        links.put(link.to(), link);
        return true;
    }

    public List<AssistantLink> links() {
        return List.copyOf(links.values());
    }

    /** Phương án đang mở ở khung 3D, hoặc {@code null} khi lượt không hỏi từ khung 3D. */
    public Long viewerPlanId() {
        return viewerPlanId;
    }

    /** Phương án đang xem, đọc một lần mỗi lượt qua {@code loader} (trống: không đọc được, ví dụ của công ty khác). */
    public Optional<ViewerPlan> viewerPlan(Supplier<Optional<ViewerPlan>> loader) {
        if (viewerPlan == null) {
            viewerPlan = viewerPlanId == null ? Optional.empty() : loader.get();
        }
        return viewerPlan;
    }

    /** Phương án đang xem nếu công cụ đã đọc trong lượt này; dịch vụ dùng để lọc lệnh lần cuối. */
    public Optional<ViewerPlan> loadedViewerPlan() {
        return viewerPlan == null ? Optional.empty() : viewerPlan;
    }

    /**
     * Thêm một lệnh cho khung 3D (đã kiểm ở công cụ). Lệnh trùng tính một lần. {@code false} khi đã đủ
     * {@value UiActionValidator#MAX_ACTIONS} lệnh.
     */
    public boolean addUiAction(UiAction action) {
        if (uiActions.contains(action)) {
            return true;
        }
        if (uiActions.size() >= UiActionValidator.MAX_ACTIONS) {
            return false;
        }
        uiActions.add(action);
        return true;
    }

    public List<UiAction> uiActions() {
        return List.copyOf(uiActions);
    }

    /** Lượt này hỏi người dùng có muốn chuyển cho nhân viên không. */
    public void offerHandoff() {
        handoffOffered = true;
    }

    public boolean handoffOffered() {
        return handoffOffered && !ticketReported;
    }

    /** Yêu cầu hỗ trợ của hội thoại (đã có từ trước hoặc vừa tạo). */
    public String ticketCode() {
        return ticketCode;
    }

    /** Công cụ đã báo mã yêu cầu hỗ trợ cho mô hình trong lượt này; câu trả lời mang mã đó. */
    public void reportTicket(String code) {
        ticketCode = code;
        ticketReported = code != null;
    }

    /** Mã yêu cầu hỗ trợ trả về cùng câu trả lời: chỉ khi lượt này tạo hoặc nhắc lại phiếu. */
    public String reportedTicketCode() {
        return ticketReported ? ticketCode : null;
    }
}
