package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTicketReplyRequest(
        @NotBlank(message = "Nội dung trả lời không được để trống")
        @Size(max = 2000, message = "Nội dung trả lời tối đa 2000 ký tự")
        String message
) {
}
