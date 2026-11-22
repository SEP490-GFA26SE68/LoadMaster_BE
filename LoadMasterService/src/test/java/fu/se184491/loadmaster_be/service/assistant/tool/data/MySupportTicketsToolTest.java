package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolCallLog;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySupportTicketsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SupportTicketService service = mock(SupportTicketService.class);
    private final MySupportTicketsTool tool = new MySupportTicketsTool(service);

    private static SupportTicketResponse ticket(int i) {
        return SupportTicketResponse.builder().id((long) i).ticketCode("TKT-%03d".formatted(i))
                .subject("Không in được nhãn QR " + i).description("Chi tiết riêng của người gửi")
                .category(TicketCategory.SYSTEM_BUG).status(TicketStatus.IN_PROGRESS).requesterId(9L)
                .requesterName("Lê Thu Trang").requesterEmail("trang@longbinh.vn").companyId(3L)
                .companyName("Vận tải Long Bình").resolutionNote("Trao đổi cũ")
                .createdAt(LocalDateTime.of(2026, 11, 18, 8, 0)).updatedAt(LocalDateTime.of(2026, 11, 19, 10, 15))
                .build();
    }

    @Test
    void ticketsAreCompactAndCappedWithTheRealTotal() {
        when(service.getTickets(null, null)).thenReturn(IntStream.rangeClosed(1, 23).mapToObj(i -> ticket(i)).toList());

        String json = ToolResults.json(tool.getMySupportTickets());
        JsonNode out = JSON.readTree(json);

        assertThat(out.get("total").asInt()).isEqualTo(23);
        assertThat(out.get("shown").asInt()).isEqualTo(MySupportTicketsTool.LIMIT);
        JsonNode first = out.get("rows").get(0);
        assertThat(first.get("ticketCode").asString()).isEqualTo("TKT-001");
        assertThat(first.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(first.get("updatedAt").asString()).isEqualTo("2026-11-19T10:15:00");
        assertThat(json).doesNotContain("description").doesNotContain("Chi tiết riêng").doesNotContain("@")
                .doesNotContain("requester").doesNotContain("resolutionNote").doesNotContain("\"id\"");
    }

    @Test
    void platformRolesDoNotGetTheToolButCompanyRolesDo() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(tool),
                mock(AssistantToolCallRepository.class), Clock.systemUTC());

        for (UserRole role : List.of(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_MANAGER, UserRole.SYSTEM_SUPPORTER)) {
            assertThat(names(registry.toolsFor(ToolScope.of(role), new ToolCallLog()))).as(role.name()).isEmpty();
        }
        assertThat(names(registry.toolsFor(ToolScope.ofGuest(), new ToolCallLog()))).isEmpty();
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.DISPATCHER,
                UserRole.WAREHOUSE_WORKER, UserRole.DRIVER)) {
            assertThat(names(registry.toolsFor(ToolScope.of(role), new ToolCallLog()))).as(role.name())
                    .containsExactly("get_my_support_tickets");
        }
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }
}
