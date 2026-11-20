package fu.se184491.loadmaster_be.service.assistant.model;

/** Màn người dùng đang mở, do trình duyệt gửi lên. Chưa kiểm: chỉ {@code AssistantPromptBuilder} đọc, và chỉ đường dẫn. */
public record PageContext(String path, String title) {
}
