package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.dto.response.support.UnlockTrialResponse;

import java.util.List;

public interface SupportTicketService {
    SupportTicketResponse createTicket(CreateSupportTicketRequest request);

    List<SupportTicketResponse> getTickets(TicketStatus status, TicketCategory category);

    SupportTicketResponse updateTicket(Long id, UpdateSupportTicketRequest request);

    int cleanupClosedTicketChatHistory();

    /**
     * Mở khóa tài khoản dùng thử đang bị FLAGGED thông qua phiếu hỗ trợ.
     * Chỉ áp dụng cho ticket có category = TRIAL_ACCOUNT_UNLOCK.
     * Kết quả trả về trạng thái tài khoản sau khi mở và hành động cần thực hiện tiếp theo.
     *
     * @param ticketId   ID của phiếu hỗ trợ loại TRIAL_ACCOUNT_UNLOCK
     * @param staffNote  Ghi chú của nhân viên hỗ trợ / quản lý khi xử lý
     * @return UnlockTrialResponse mô tả kết quả: trạng thái mới, còn hạn hay hết hạn dùng thử
     */
    UnlockTrialResponse unlockTrialAccount(Long ticketId, String staffNote);
}
