package fu.se184491.loadmaster_be.dto.response.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/** Một dòng nhật ký hoạt động. {@code companyId} null: không thuộc công ty nào. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private Long id;
    private String actionType;
    private String entityName;
    private String entityId;
    /** Mã chuyến (TRIP-…) khi bản ghi nói về một chuyến, điểm giao hay phương án của chuyến; còn lại null. */
    private String entityCode;
    private Long userId;
    private String userName;
    private String userRole;
    private Long companyId;
    private String companyName;
    private Map<String, Object> oldValues;
    private Map<String, Object> newValues;
    private String ipAddress;
    private Instant createdAt;
}
