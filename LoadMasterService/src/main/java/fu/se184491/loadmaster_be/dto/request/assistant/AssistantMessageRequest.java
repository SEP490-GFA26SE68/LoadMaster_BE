package fu.se184491.loadmaster_be.dto.request.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Câu hỏi trong ứng dụng. {@code page} và {@code viewer} do trình duyệt gửi: chỉ là gợi ý, trường sai dạng bị bỏ khi dựng
 * ngữ cảnh, và tiêu đề trang không bao giờ được đưa cho mô hình. Vai trò, công ty, gói lấy từ máy chủ, không từ đây.
 */
public record AssistantMessageRequest(
        @Size(max = 64, message = "INVALID_INPUT") String conversationId,
        @NotBlank(message = "INVALID_INPUT") @Size(max = 20_000, message = "ASSISTANT_MESSAGE_TOO_LONG") String message,
        @Size(max = 16, message = "INVALID_INPUT") String locale,
        Page page,
        Viewer viewer
) {

    public record Page(String path, String title) {
    }

    public record Viewer(String tripCode, Long loadPlanId, String selectedPackageCode) {
    }
}
