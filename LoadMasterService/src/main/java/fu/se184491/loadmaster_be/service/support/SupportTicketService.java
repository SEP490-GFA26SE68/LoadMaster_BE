package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;

import java.util.List;

public interface SupportTicketService {
    SupportTicketResponse createTicket(CreateSupportTicketRequest request);

    List<SupportTicketResponse> getTickets(TicketStatus status, TicketCategory category);

    SupportTicketResponse updateTicket(Long id, UpdateSupportTicketRequest request);
}
