package fu.se184491.loadmaster_be.dto.request.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Câu hỏi của khách ở trang công khai. {@code guestKey} là mã ngẫu nhiên trình duyệt giữ trong phiên; hội thoại của khách
 * chỉ đọc tiếp được với đúng mã đó. Giới hạn độ dài thật (đếm theo ký tự) nằm ở service; {@code @Size} ở đây chỉ chặn
 * thân quá lớn trước khi xử lý.
 */
public record AssistantPublicMessageRequest(
        @Size(max = 64, message = "INVALID_INPUT") String conversationId,
        @NotBlank(message = "INVALID_INPUT") @Size(max = 20_000, message = "ASSISTANT_MESSAGE_TOO_LONG") String message,
        @Size(max = 16, message = "INVALID_INPUT") String locale,
        @NotNull(message = "INVALID_INPUT")
        @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "INVALID_INPUT") String guestKey
) {
}
