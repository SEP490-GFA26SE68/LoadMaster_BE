package fu.se184491.loadmaster_be.entity.assistant;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Một lần trợ lý gọi công cụ. Không lưu kết quả công cụ. */
@Entity
@Table(name = "assistant_tool_calls", indexes = {
        @Index(name = "idx_assistant_tool_calls_message_id", columnList = "message_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantToolCall {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "tool_name", length = 60, nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb")
    private Map<String, Object> arguments;

    @Column(name = "ok", nullable = false)
    private boolean ok;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
