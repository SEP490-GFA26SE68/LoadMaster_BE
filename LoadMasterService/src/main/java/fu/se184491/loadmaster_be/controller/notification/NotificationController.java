package fu.se184491.loadmaster_be.controller.notification;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.notification.MarkNotificationsReadRequest;
import fu.se184491.loadmaster_be.dto.response.notification.NotificationListResponse;
import fu.se184491.loadmaster_be.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Notification", description = "Chuông thông báo của người đang đăng nhập")
public class NotificationController {

    private final NotificationService notificationService;

    /** Thông báo của 7 ngày gần nhất theo vai trò (tối đa 20, mới nhất trước) và số chưa đọc. */
    @GetMapping("/api/notifications")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Thông báo của người đang đăng nhập")
    public ApiResponse<NotificationListResponse> getNotifications() {
        return ApiResponse.success("Lấy thông báo thành công", notificationService.list());
    }

    /** Đánh dấu đã đọc các thông báo (mã dòng nhật ký); trả lại danh sách mới. */
    @PostMapping("/api/notifications/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Đánh dấu thông báo đã đọc")
    public ApiResponse<NotificationListResponse> markRead(@Valid @RequestBody MarkNotificationsReadRequest request) {
        return ApiResponse.success("Đã đánh dấu đã đọc", notificationService.markRead(request.ids()));
    }
}
