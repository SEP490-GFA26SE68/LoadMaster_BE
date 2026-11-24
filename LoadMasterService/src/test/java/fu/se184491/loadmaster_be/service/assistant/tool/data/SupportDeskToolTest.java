package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.entity.support.TicketReply;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Bàn hỗ trợ trên H2: phiếu của mọi công ty, lọc trạng thái / loại / công ty, trao đổi gần nhất, không email. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class SupportDeskToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 8, 0);

    @Autowired private EntityManager entityManager;
    @Autowired private SupportDeskTool tool;

    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        ToolFixtures data = new ToolFixtures(entityManager);
        Company longBinh = data.company("AST-SD-LB", "Vận tải Long Bình");
        Company phuongNam = data.company("AST-SD-PN", "Vận tải Phương Nam");
        User trang = data.user(longBinh, "ast-sd-trang", UserRole.DISPATCHER, "Lê Thu Trang");
        User viet = data.user(phuongNam, "ast-sd-viet", UserRole.ADMIN, "Lâm Viết");
        User supporter = data.user(null, "ast-sd-support", UserRole.SYSTEM_SUPPORTER, "Trần Hỗ Trợ");
        SupportTicket printing = ticket("TKT-AST-01", trang, supporter, TicketCategory.SYSTEM_BUG,
                TicketStatus.IN_PROGRESS, AT);
        ticket("TKT-AST-02", viet, null, TicketCategory.BILLING, TicketStatus.OPEN, AT.plusHours(1));
        ticket("TKT-AST-03", viet, supporter, TicketCategory.BILLING, TicketStatus.CLOSED, AT.plusHours(2));
        for (int i = 0; i < 18; i++) {
            entityManager.persist(TicketReply.builder().ticket(printing).authorId(trang.getId())
                    .authorName(i % 2 == 0 ? "Lê Thu Trang" : "Trần Hỗ Trợ")
                    .authorRole(i % 2 == 0 ? "DISPATCHER" : "SYSTEM_SUPPORTER").message("Trao đổi số " + i)
                    .createdAt(AT.plusMinutes(i)).build());
        }
        entityManager.flush();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsEveryCompanysTicketsNewestFirstWithoutEmails() {
        ToolFixtures.signIn("ast-sd-support");

        String json = ToolResults.json(tool.listSupportTickets(null, null, null));
        JsonNode out = JSON.readTree(json);

        assertThat(codes(out)).containsSubsequence("TKT-AST-03", "TKT-AST-02", "TKT-AST-01");
        assertThat(json).doesNotContain("@").doesNotContain("\"id\"").doesNotContain("description");
    }

    @Test
    void filtersByStatusCategoryAndCompany() {
        ToolFixtures.signIn("ast-sd-support");

        JsonNode open = JSON.readTree(ToolResults.json(tool.listSupportTickets("open", null, "ast-sd")));
        JsonNode billing = JSON.readTree(ToolResults.json(tool.listSupportTickets(null, "BILLING", "phương nam")));
        JsonNode longBinh = JSON.readTree(ToolResults.json(tool.listSupportTickets(null, null, "AST-SD-LB")));

        assertThat(codes(open)).containsExactly("TKT-AST-02");
        assertThat(codes(billing)).containsExactly("TKT-AST-03", "TKT-AST-02");
        assertThat(codes(longBinh)).containsExactly("TKT-AST-01");
        assertThat(longBinh.get("rows").get(0).get("companyName").asString()).isEqualTo("Vận tải Long Bình");
        assertThat(tool.listSupportTickets("DONE", null, null)).isEqualTo(ToolResults.error(ToolResults.INVALID_FILTER));
    }

    @Test
    void oneTicketCarriesTheLatestRepliesAndTheRealCount() {
        ToolFixtures.signIn("ast-sd-support");

        String json = ToolResults.json(tool.getSupportTicket(" tkt-ast-01 "));
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("ticketCode").asString()).isEqualTo("TKT-AST-01");
        assertThat(out.get("replyCount").asInt()).isEqualTo(18);
        assertThat(out.get("replies")).hasSize(SupportDeskTool.MAX_REPLIES);
        assertThat(out.get("replies").get(0).get("message").asString()).isEqualTo("Trao đổi số 3");
        assertThat(out.get("description").asString()).hasSize(SupportDeskTool.MAX_TEXT + 1);
        assertThat(json).doesNotContain("@").doesNotContain("authorId").doesNotContain("\"id\"");
    }

    @Test
    void unknownTicketIsNotFound() {
        ToolFixtures.signIn("ast-sd-support");

        assertThat(tool.getSupportTicket("TKT-NONE")).isEqualTo(ToolResults.error("SUPPORT_TICKET_NOT_FOUND"));
        assertThat(tool.getSupportTicket(" ")).isEqualTo(ToolResults.error("SUPPORT_TICKET_NOT_FOUND"));
    }

    private SupportTicket ticket(String code, User requester, User assignee, TicketCategory category,
                                 TicketStatus status, LocalDateTime createdAt) {
        SupportTicket ticket = SupportTicket.builder().ticketCode(code).requester(requester).assignedTo(assignee)
                .category(category).priority(TicketPriority.MEDIUM).status(status)
                .subject("Không in được nhãn QR " + code).description("x".repeat(700)).createdAt(createdAt)
                .updatedAt(createdAt).build();
        entityManager.persist(ticket);
        return ticket;
    }

    private static java.util.List<String> codes(JsonNode out) {
        java.util.List<String> codes = new java.util.ArrayList<>();
        out.get("rows").forEach(row -> codes.add(row.get("ticketCode").asString()));
        return codes;
    }
}
