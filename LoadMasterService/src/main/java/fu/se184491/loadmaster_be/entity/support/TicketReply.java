package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.entity.account.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một lần trả lời trong cuộc trao đổi của yêu cầu hỗ trợ. Tên và vai trò người trả lời chụp lúc trả lời: người của công ty không đọc
 * được tài khoản nền tảng, và tài khoản có thể đổi tên sau đó.
 */
@Entity
@Table(name = "ticket_replies", indexes = @Index(name = "idx_ticket_replies_ticket", columnList = "ticket_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id")
    private User author;

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
