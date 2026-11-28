package fu.se184491.loadmaster_be.service.assistant.tool.action;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.output.LinkTarget;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolCallLog;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoffToolTest {

    private static final long CONVERSATION = 41L;

    private final SupportTicketService tickets = mock(SupportTicketService.class);
    private final AssistantConversationRepository conversations = mock(AssistantConversationRepository.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(status);
        }
    };
    private final HandoffTool tool = new HandoffTool(tickets, conversations, transactions);

    @BeforeEach
    void ticketsGetCodes() {
        when(tickets.createTicket(any())).thenReturn(SupportTicketResponse.builder().ticketCode("TKT-0042").build());
        when(conversations.claimHandoffTicket(anyLong(), anyString())).thenReturn(1);
    }

    private static AssistantTurn turn(boolean invited, String ticketCode, List<AssistantTurn.Line> recent) {
        return new AssistantTurn(ToolScope.of(UserRole.DISPATCHER), "vi", CONVERSATION, invited, ticketCode, recent);
    }

    private static ToolContext context(AssistantTurn turn) {
        return new ToolContext(Map.of(AssistantTurn.CONTEXT_KEY, turn));
    }

    private HandoffTool.Result ask(AssistantTurn turn, String summary, String category) {
        return tool.requestHumanSupport(summary, category, context(turn));
    }

    @Test
    void firstCallOnlyOffersAndCreatesNothing() {
        AssistantTurn turn = turn(false, null, List.of(new AssistantTurn.Line(true, "Cho tôi gặp nhân viên")));

        HandoffTool.Result result = ask(turn, "Muốn gặp nhân viên", "SYSTEM_BUG");

        assertThat(result.status()).isEqualTo("NEED_CONFIRMATION");
        assertThat(result.ticketCode()).isNull();
        assertThat(turn.handoffOffered()).isTrue();
        assertThat(turn.reportedTicketCode()).isNull();
        verify(tickets, never()).createTicket(any());
    }

    @Test
    void newConversationCannotBeInvitedYet() {
        AssistantTurn turn = new AssistantTurn(ToolScope.of(UserRole.DRIVER), "vi", null, true, null, List.of());

        assertThat(ask(turn, "Lỗi đối chiếu kiện", "SYSTEM_BUG").status()).isEqualTo("NEED_CONFIRMATION");
        verify(tickets, never()).createTicket(any());
    }

    @Test
    void afterAnInvitationExactlyOneTicketIsCreatedAndRepeatedCallsReturnIt() {
        AssistantTurn turn = turn(true, null, List.of(new AssistantTurn.Line(true, "Có, chuyển giúp tôi")));

        HandoffTool.Result first = ask(turn, "Không duyệt được phương án", "ALGORITHM_ERROR");
        HandoffTool.Result again = ask(turn, "Không duyệt được phương án", "ALGORITHM_ERROR");

        assertThat(first).isEqualTo(new HandoffTool.Result("CREATED", "TKT-0042"));
        assertThat(again).isEqualTo(new HandoffTool.Result("ALREADY_CREATED", "TKT-0042"));
        assertThat(turn.reportedTicketCode()).isEqualTo("TKT-0042");
        assertThat(turn.handoffOffered()).isFalse();
        verify(tickets, times(1)).createTicket(any());
        verify(conversations).claimHandoffTicket(CONVERSATION, "TKT-0042");
        assertThat(status.isRollbackOnly()).isFalse();
    }

    @Test
    void conversationWithATicketReturnsItWithoutCreatingAnother() {
        AssistantTurn turn = turn(true, "TKT-0007", List.of());

        assertThat(ask(turn, "Vẫn lỗi", "SYSTEM_BUG")).isEqualTo(new HandoffTool.Result("ALREADY_CREATED", "TKT-0007"));
        assertThat(turn.reportedTicketCode()).isEqualTo("TKT-0007");
        verify(tickets, never()).createTicket(any());
    }

    @Test
    void lostClaimRollsTheNewTicketBackAndReturnsTheWinner() {
        when(conversations.claimHandoffTicket(anyLong(), anyString())).thenReturn(0);
        AssistantConversation winner = AssistantConversation.builder().id(CONVERSATION).handoffTicketCode("TKT-0041")
                .build();
        when(conversations.findById(CONVERSATION)).thenReturn(Optional.of(winner));
        AssistantTurn turn = turn(true, null, List.of());

        assertThat(ask(turn, "Lỗi", "SYSTEM_BUG")).isEqualTo(new HandoffTool.Result("ALREADY_CREATED", "TKT-0041"));
        assertThat(status.isRollbackOnly()).isTrue();
        assertThat(turn.reportedTicketCode()).isEqualTo("TKT-0041");
    }

    @Test
    void longSummaryIsCutAndDescriptionCarriesTheTenLatestMessages() {
        List<AssistantTurn.Line> recent = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new AssistantTurn.Line(i % 2 == 1, "tin số " + i)).toList();
        String summary = "Không in được nhãn QR cho chuyến TRIP-2026-0914. ".repeat(8);

        ask(turn(true, null, recent), summary, "billing");

        ArgumentCaptor<CreateSupportTicketRequest> request = ArgumentCaptor.forClass(CreateSupportTicketRequest.class);
        verify(tickets).createTicket(request.capture());
        assertThat(summary.length()).isGreaterThan(380);
        assertThat(request.getValue().subject().codePointCount(0, request.getValue().subject().length()))
                .isLessThanOrEqualTo(HandoffTool.SUBJECT_MAX);
        assertThat(request.getValue().category()).isEqualTo(TicketCategory.BILLING);
        assertThat(request.getValue().priority()).isNull();
        String description = request.getValue().description();
        assertThat(description).startsWith(summary.strip())
                .contains("Người dùng: tin số 3").contains("Lumo: tin số 12")
                .doesNotContain("tin số 2\n").doesNotContain("tin số 1\n");
    }

    @Test
    void categoriesOutsideTheThreeAllowedBecomeSystemBug() {
        assertThat(HandoffTool.categoryOf("TRIAL_ACCOUNT_UNLOCK")).isEqualTo(TicketCategory.SYSTEM_BUG);
        assertThat(HandoffTool.categoryOf("refund")).isEqualTo(TicketCategory.SYSTEM_BUG);
        assertThat(HandoffTool.categoryOf(null)).isEqualTo(TicketCategory.SYSTEM_BUG);
        assertThat(HandoffTool.categoryOf(" algorithm_error ")).isEqualTo(TicketCategory.ALGORITHM_ERROR);
    }

    @Test
    void blankSummaryIsRejected() {
        assertThatThrownBy(() -> ask(turn(true, null, List.of()), "  ", "SYSTEM_BUG"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(tickets, never()).createTicket(any());
    }

    @Test
    void registryHandsTheTurnToBothActionToolsAndKeepsItOutOfTheSchema() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(tool, new LinkTool()),
                mock(AssistantToolCallRepository.class), Clock.systemUTC());
        AssistantTurn turn = turn(false, null, List.of());
        Map<String, ToolCallback> byName = registry.toolsFor(turn, new ToolCallLog()).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.getToolDefinition().name(), t -> t));

        for (ToolCallback callback : byName.values()) {
            assertThat(callback.getToolDefinition().inputSchema()).as(callback.getToolDefinition().name())
                    .doesNotContain("context").doesNotContain("ToolContext");
        }
        assertThat(byName.get("suggest_link").getToolDefinition().inputSchema()).contains("OPTIMIZATION_SETUP");

        assertThat(byName.get("request_human_support").call("{\"summary\":\"Lỗi\",\"category\":\"SYSTEM_BUG\"}"))
                .isEqualTo("{\"status\":\"NEED_CONFIRMATION\"}");
        assertThat(byName.get("suggest_link").call("{\"target\":\"TRIPS\"}"))
                .isEqualTo("{\"status\":\"ADDED\",\"label\":\"Chuyến hàng\"}");
        assertThat(turn.handoffOffered()).isTrue();
        assertThat(turn.links()).containsExactly(new AssistantLink("Chuyến hàng", "/chuyen"));
    }

    @Test
    void linksAreCappedAtThreeAndDuplicatesCountOnce() {
        LinkTool links = new LinkTool();
        AssistantTurn turn = turn(false, null, List.of());
        ToolContext context = context(turn);

        assertThat(links.suggestLink(LinkTarget.TRIPS, null,
                context).status()).isEqualTo("ADDED");
        assertThat(links.suggestLink(LinkTarget.TRIPS, null,
                context).status()).isEqualTo("ADDED");
        assertThat(links.suggestLink(LinkTarget.MONITORING, null,
                context).status()).isEqualTo("ADDED");
        assertThat(links.suggestLink(LinkTarget.TRIP_DETAIL,
                "TRIP-2026-0914", context).status()).isEqualTo("ADDED");
        assertThat(links.suggestLink(LinkTarget.PACKAGES, null,
                context).status()).isEqualTo("LIMIT_REACHED");
        assertThat(links.suggestLink(LinkTarget.BILLING, null,
                context).status()).isEqualTo("NOT_AVAILABLE");
        assertThat(turn.links()).extracting(AssistantLink::to)
                .containsExactly("/chuyen", "/giam-sat", "/chuyen/TRIP-2026-0914");
    }
}
