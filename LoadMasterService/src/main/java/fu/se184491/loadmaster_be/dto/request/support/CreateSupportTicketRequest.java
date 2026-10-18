package fu.se184491.loadmaster_be.dto.request.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSupportTicketRequest(
        @NotBlank
        String subject,
        String description,
        @NotNull
        TicketCategory category,
        TicketPriority priority
) {
}
