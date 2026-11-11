package fu.se184491.loadmaster_be.entity.support;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một lần trả lời trong cuộc trao đổi của yêu cầu hỗ trợ. Tên và vai trò người trả lời chụp lúc trả lời: người của công ty không đọc
 * được tài khoản nền tảng, và tài khoản có thể đổi tên sau đó. Người trả lời chỉ lưu mã, không khoá ngoại tới {@code users}: xoá tài
 * khoản (tài khoản dùng thử hết hạn, tài khoản ngừng dùng quá 3 tháng) không bị các lần trả lời cũ chặn.
 */
@Entity
@Table(name = "ticket_replies", indexes = @Index(name = "idx_ticket_replies_ticket", columnList = "ticket_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "author_name", length = 255)
    private String authorName;

    /** Mã vai trò lúc trả lời (UserRole). */
    @Column(name = "author_role", length = 50)
    private String authorRole;

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", length = 19, columnDefinition = "varchar(19)", nullable = false)
    private LocalDateTime createdAt;
}
