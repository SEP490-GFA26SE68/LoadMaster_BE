package fu.se184491.loadmaster_be.entity.common;

import fu.se184491.loadmaster_be.entity.account.User;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_company_created", columnList = "company_id, created_at")
})
@Immutable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id")
    private User user;

    /**
     * Công ty của sự kiện: việc trên một tài khoản thuộc công ty của tài khoản đó, việc vận hành
     * thuộc công ty của bản ghi (chuyến, kiện…); {@code null} khi không thuộc công ty nào.
     * Bản ghi cũ chưa có cột này thì lúc đọc lấy công ty của người làm.
     */
    @Column(name = "company_id") private Long companyId;

    @Column(name = "action_type", length = 50) private String actionType;
    @Column(name = "entity_name", length = 50) private String entityName;
    @Column(name = "entity_id", length = 50) private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "jsonb")
    private Map<String, Object> oldValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "jsonb")
    private Map<String, Object> newValues;

    @Column(name = "ip_address", length = 50) private String ipAddress;
    @Column(name = "created_at") private Instant createdAt;
}
