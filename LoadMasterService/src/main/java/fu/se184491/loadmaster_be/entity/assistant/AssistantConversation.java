package fu.se184491.loadmaster_be.entity.assistant;

import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Một hội thoại với trợ lý. Khách nhận diện bằng {@code guestKey} (trình duyệt tự sinh), người đăng nhập bằng
 * {@code userId}. Ra ngoài chỉ dùng {@code publicId}, không lộ khoá số.
 */
@Entity
@Table(name = "assistant_conversations", indexes = {
        @Index(name = "idx_assistant_conversations_user_id", columnList = "user_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 40, nullable = false, unique = true)
    private String publicId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "guest_key", length = 64)
    private String guestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    private AssistantChannel channel;

    /** Mã yêu cầu hỗ trợ đã tạo khi chuyển nhân viên; mỗi hội thoại một phiếu. */
    @Column(name = "handoff_ticket_code", length = 40)
    private String handoffTicketCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
