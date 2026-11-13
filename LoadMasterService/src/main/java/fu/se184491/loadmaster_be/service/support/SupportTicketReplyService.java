package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.dto.response.support.SupportTicketThreadResponse;

public interface SupportTicketReplyService {
    /** Cuộc trao đổi của một yêu cầu: người của công ty chỉ đọc yêu cầu của chính mình, nhân sự nền tảng đọc mọi yêu cầu. */
    SupportTicketThreadResponse getThread(Long ticketId);

    /** Trả lời một yêu cầu, cùng luật trạng thái với lần trả lời qua PATCH /api/support-tickets/{id}. */
    SupportTicketThreadResponse reply(Long ticketId, String message);
}
