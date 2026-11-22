package fu.se184491.loadmaster_be.service.assistant.model;

import fu.se184491.loadmaster_be.constant.account.UserRole;

/**
 * Người hỏi theo phía máy chủ (đọc từ token, không từ thân request). Khách chưa đăng nhập thì không có đối tượng này.
 * {@code companyId}, {@code companyName}, {@code planName} có thể {@code null}.
 */
public record AssistantCaller(Long userId, Long companyId, UserRole role, String companyName, String planName) {
}
