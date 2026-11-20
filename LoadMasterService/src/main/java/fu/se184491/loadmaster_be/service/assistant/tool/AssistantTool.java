package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.constant.account.UserRole;

import java.util.Set;

/**
 * Bean chứa công cụ của trợ lý. Mỗi phương thức mang {@code @Tool} (tên snake_case, mô tả cho mô hình) là một công cụ;
 * ai được dùng khai một lần cho cả bean. Công cụ chạy trên luồng của request nên đọc người hỏi từ
 * {@code SecurityContext} như mọi service, không nhận mã người dùng hay công ty làm tham số. Lỗi nghiệp vụ ném
 * {@code AppException}: vòng gọi trả mã cho mô hình.
 */
public interface AssistantTool {

    /** Vai trò được dùng mọi công cụ của bean. */
    Set<UserRole> roles();

    /** Khách chưa đăng nhập (trang công khai) có được dùng không. */
    default boolean guest() {
        return false;
    }
}
