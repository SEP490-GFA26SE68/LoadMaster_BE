package fu.se184491.loadmaster_be.dto.request.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Nội dung điều phối viên dán vào (tin nhắn, email của khách) để đọc thành bản nháp yêu cầu giao. Giới hạn 20.000 ký tự
 * chỉ chặn thân request quá lớn; giới hạn thật ({@code assistant.max-draft-chars}, đếm theo ký tự Unicode) kiểm ở
 * service.
 */
public record RequirementDraftRequest(
        @NotBlank(message = "INVALID_INPUT")
        @Size(max = 20_000, message = "ASSISTANT_MESSAGE_TOO_LONG")
        String text
) {
}
