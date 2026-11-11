package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.entity.support.TicketReply;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.repository.support.TicketReplyRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Trả lời ở bảng ticket_replies đi theo vòng đời của phiếu trên cơ sở dữ liệu thật (H2): hết hạn giữ lịch sử, xoá phiếu. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ticket-reply-cleanup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret"
})
@Transactional
class TicketReplyCleanupIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SupportTicketService supportTicketService;

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private TicketReplyRepository ticketReplyRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    @DisplayName("Hết hạn giữ lịch sử trao đổi: trả lời ở bảng của phiếu đó bị xoá, phiếu đóng gần đây giữ nguyên")
    void cleanupDeletesTheRepliesOfAnExpiredTicket() {
        User requester = persistUser("kc-req", "req@acme.vn");
        SupportTicket expired = persistTicket("TCK-OLD", requester, TicketStatus.RESOLVED, now.minusDays(20));
        SupportTicket recent = persistTicket("TCK-NEW", requester, TicketStatus.CLOSED, now.minusDays(2));
        persistReply(expired, requester, "Lần một");
        persistReply(expired, requester, "Lần hai");
        persistReply(recent, requester, "Còn giữ");
        entityManager.flush();

        int cleaned = supportTicketService.cleanupClosedTicketChatHistory();

        entityManager.flush();
        entityManager.clear();
        assertThat(cleaned).isEqualTo(1);
        assertThat(ticketReplyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(expired.getId())).isEmpty();
        assertThat(ticketReplyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(recent.getId()))
                .extracting(TicketReply::getMessage).containsExactly("Còn giữ");
        assertThat(supportTicketRepository.findById(expired.getId())).isPresent();
    }

    @Test
    @DisplayName("Xoá phiếu của một tài khoản: trả lời xoá trước nên khoá ngoại không chặn, không còn dòng mồ côi; xoá tài khoản không vấp trả lời")
    void deletingTheTicketsOfAnAccountRemovesTheirRepliesFirst() {
        User requester = persistUser("kc-trial", "trial@acme.vn");
        User supporter = persistUser("kc-sup", "sup@loadmaster.vn");
        SupportTicket ticket = persistTicket("TCK-TRIAL", requester, TicketStatus.OPEN, null);
        persistReply(ticket, requester, "Mở khoá giúp tôi");
        persistReply(ticket, supporter, "Đã nhận");
        entityManager.flush();

        List<Long> ids = supportTicketRepository.findIdsByRequesterId(requester.getId());
        ticketReplyRepository.deleteByTicketIdIn(ids);
        supportTicketRepository.deleteAllByIdInBatch(ids);
        entityManager.remove(entityManager.merge(requester));
        // Người trả lời chỉ lưu mã: xoá tài khoản hỗ trợ cũng không vấp khoá ngoại
        entityManager.remove(entityManager.merge(supporter));
        entityManager.flush();
        entityManager.clear();

        assertThat(ids).containsExactly(ticket.getId());
        assertThat(supportTicketRepository.findById(ticket.getId())).isEmpty();
        assertThat(ticketReplyRepository.count()).isZero();
    }

    private User persistUser(String keycloakId, String email) {
        User user = User.builder()
                .keycloakId(keycloakId)
                .email(email)
                .fullName("Người " + keycloakId)
                .userRoleType(UserRole.DISPATCHER)
                .status(UserStatus.ACTIVE)
                .createdAt(now.minusDays(40))
                .updatedAt(now.minusDays(40))
                .build();
        entityManager.persist(user);
        return user;
    }

    private SupportTicket persistTicket(String code, User requester, TicketStatus status, LocalDateTime resolvedAt) {
        SupportTicket ticket = SupportTicket.builder()
                .ticketCode(code)
                .requester(requester)
                .subject("Phiếu " + code)
                .status(status)
                .createdAt(resolvedAt != null ? resolvedAt.minusDays(1) : now.minusDays(30))
                .resolvedAt(resolvedAt)
                .build();
        entityManager.persist(ticket);
        return ticket;
    }

    private void persistReply(SupportTicket ticket, User author, String message) {
        entityManager.persist(TicketReply.builder()
                .ticket(ticket)
                .authorId(author.getId())
                .authorName(author.getFullName())
                .authorRole(author.getUserRoleType().name())
                .message(message)
                .createdAt(now.minusDays(25))
                .build());
    }
}
