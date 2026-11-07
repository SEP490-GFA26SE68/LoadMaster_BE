package fu.se184491.loadmaster_be.dto.response.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Thông báo của người đang đăng nhập (mới nhất trước) và số chưa đọc trong đó. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationListResponse {
    private List<NotificationResponse> items;
    private int unreadCount;
    /** Số ngày gần nhất được xét. */
    private int windowDays;
}
