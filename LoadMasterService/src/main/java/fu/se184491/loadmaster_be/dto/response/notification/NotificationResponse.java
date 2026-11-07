package fu.se184491.loadmaster_be.dto.response.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Một thông báo: dòng nhật ký mà người xem nhận được theo vai trò, kèm đã đọc hay chưa.
 * {@code id} là mã dòng nhật ký. {@code tripId} / {@code tripCode}: chuyến mà sự kiện nói tới (null khi không có).
 * {@code actorName} null: việc của hệ thống.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {
    private Long id;
    private String actionType;
    private String entityName;
    private String entityId;
    private Long tripId;
    private String tripCode;
    private Long actorId;
    private String actorName;
    private String actorRole;
    private Map<String, Object> oldValues;
    private Map<String, Object> newValues;
    private Instant createdAt;
    private boolean read;
}
