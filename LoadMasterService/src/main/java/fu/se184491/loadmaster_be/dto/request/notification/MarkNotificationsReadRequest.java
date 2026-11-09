package fu.se184491.loadmaster_be.dto.request.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Mã các thông báo (mã dòng nhật ký) vừa đọc. Mã không thuộc thông báo của người gọi bị bỏ qua. */
public record MarkNotificationsReadRequest(@NotNull @Size(max = 100) List<Long> ids) {
}
