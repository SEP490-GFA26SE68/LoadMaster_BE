package fu.se184491.loadmaster_be.dto.request.support;

import fu.se184491.loadmaster_be.constant.support.TicketStatus;

public record UpdateSupportTicketRequest(
        TicketStatus status,
        Long assignedToId,
        String resolutionNote
) {
}
