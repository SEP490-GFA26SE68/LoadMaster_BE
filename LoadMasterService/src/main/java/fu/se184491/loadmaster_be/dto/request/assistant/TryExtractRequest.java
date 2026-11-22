package fu.se184491.loadmaster_be.dto.request.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mô tả hàng bằng lời của khách ở phần "Thử với hàng của bạn". Giới hạn 20.000 ký tự chỉ chặn thân request quá lớn;
 * giới hạn thật ({@code assistant.max-message-chars}, đếm theo ký tự Unicode) kiểm ở service.
 */
public record TryExtractRequest(
        @NotBlank(message = "INVALID_INPUT")
        @Size(max = 20_000, message = "ASSISTANT_MESSAGE_TOO_LONG")
        String text,

        @Size(max = 16, message = "INVALID_INPUT")
        String locale,

        @NotNull(message = "INVALID_INPUT")
        @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "INVALID_INPUT")
        String guestKey
) {
}
