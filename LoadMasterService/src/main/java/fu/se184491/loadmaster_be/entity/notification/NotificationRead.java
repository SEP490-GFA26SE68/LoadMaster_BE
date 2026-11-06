package fu.se184491.loadmaster_be.entity.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Một thông báo người dùng đã đọc. Thông báo là dòng nhật ký ({@code audit_logs}) mà người đó nhận
 * được theo vai trò, nên chỉ cần ghi lại "người nào đã đọc dòng nào".
 */
@Entity
@Table(name = "notification_reads",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_reads_user_log", columnNames = {"user_id", "audit_log_id"}),
        indexes = @Index(name = "idx_notification_reads_user", columnList = "user_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRead {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "audit_log_id", nullable = false)
    private Long auditLogId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;
}
