package fu.se184491.loadmaster_be.entity.assistant;

import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Một tin trong hội thoại với trợ lý. Tin trợ lý mang số token của lượt đó (ngân sách ngày cộng từ đây). */
@Entity
@Table(name = "assistant_messages", indexes = {
        @Index(name = "idx_assistant_messages_conversation_id", columnList = "conversation_id"),
        @Index(name = "idx_assistant_messages_created_at", columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 10, nullable = false)
    private AssistantMessageRole role;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Lệnh giao diện đã qua kiểm của tin trợ lý. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_actions", columnDefinition = "jsonb")
    private List<Map<String, Object>> uiActions;

    /** Tin trợ lý này có mời chuyển sang nhân viên hỗ trợ không. */
    @Builder.Default
    @Column(name = "handoff_offered", nullable = false)
    private boolean handoffOffered = false;

    @Builder.Default
    @Column(name = "input_tokens", nullable = false)
    private int inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens", nullable = false)
    private int outputTokens = 0;

    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
