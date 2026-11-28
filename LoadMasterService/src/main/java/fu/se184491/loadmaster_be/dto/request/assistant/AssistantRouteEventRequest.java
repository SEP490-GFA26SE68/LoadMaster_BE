package fu.se184491.loadmaster_be.dto.request.assistant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Trang công khai báo một câu trả lời không cần mô hình ({@code SMALL_TALK}, {@code PREPARED}) hoặc khách bấm "hỏi chi
 * tiết" ({@code ESCALATED}). Chỉ để đếm: không mang chữ, mã khách hay gì nhận diện người hỏi.
 */
public record AssistantRouteEventRequest(
        @NotNull(message = "INVALID_INPUT")
        @Pattern(regexp = "^(SMALL_TALK|PREPARED|ESCALATED)$", message = "INVALID_INPUT") String route,
        @Pattern(regexp = "^[a-zA-Z]{1,40}$", message = "INVALID_INPUT") String topicId,
        @NotNull(message = "INVALID_INPUT")
        @Pattern(regexp = "^(vi|en)$", message = "INVALID_INPUT") String locale
) {
}
