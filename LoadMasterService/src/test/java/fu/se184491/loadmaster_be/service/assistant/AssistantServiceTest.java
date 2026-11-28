package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantToolCallRepository;
import fu.se184491.loadmaster_be.entity.assistant.AssistantToolCall;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.loop.AssistantLoop;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.model.PageContext;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import fu.se184491.loadmaster_be.service.assistant.support.FakeChatModel;
import fu.se184491.loadmaster_be.service.assistant.support.MutableClock;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolCallLog;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import fu.se184491.loadmaster_be.service.assistant.tool.action.HandoffTool;
import fu.se184491.loadmaster_be.service.assistant.tool.action.LinkTool;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.model.Handoff;
import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;
import fu.se184491.loadmaster_be.service.assistant.model.ViewerContext;
import fu.se184491.loadmaster_be.service.assistant.tool.action.ViewerActionTools;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import jakarta.persistence.EntityManager;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Một lượt hỏi không công cụ trên H2, với mô hình giả. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class AssistantServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-20T03:00:00Z");
    private static final String GUEST_KEY = "guest-key-0123456789";
    private static final AssistantCaller DISPATCHER =
            new AssistantCaller(8L, 1L, UserRole.DISPATCHER, "Vận tải Long Bình", "Pro");

    @Autowired private AssistantConversationRepository conversations;
    @Autowired private AssistantMessageRepository messages;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AssistantPromptBuilder promptBuilder;
    @Autowired private AssistantLoop loop;
    @Autowired private AssistantToolCallRepository toolCalls;
    @Autowired private AssistantToolRegistry registry;
    @Autowired private EntityManager entityManager;

    @MockitoBean private JwtDecoder jwtDecoder;

    private final FakeChatModel model = new FakeChatModel();
    private final AssistantProperties properties = new AssistantProperties();
    private final RecordingBudget budget = new RecordingBudget();
    private final List<String> rateKeys = new ArrayList<>();
    private final MutableClock clock = new MutableClock(T0);
    private final TripTools tripTools = new TripTools();

    @BeforeEach
    void enable() {
        properties.setEnabled(true);
    }

    private AssistantService service(ChatModel chatModel, AssistantTool... extra) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                chatModel == null ? Map.<String, Object>of() : Map.<String, Object>of("assistantChatModel", chatModel));
        ObjectProvider<ChatModel> provider = beans.getBeanProvider(ChatModel.class);
        AssistantRateLimiter limiter = (key, limit) -> rateKeys.add(key + "@" + limit);
        List<AssistantTool> all = new ArrayList<>(List.of(tripTools));
        all.addAll(List.of(extra));
        AssistantToolRegistry tools = new AssistantToolRegistry(all, toolCalls, clock);
        return new AssistantService(provider, properties, limiter, budget, promptBuilder, loop, tools,
                conversations, messages, transactionManager, clock);
    }

    private static AssistantAskCommand guest(String conversationId, String message, String guestKey) {
        return new AssistantAskCommand(conversationId, message, "vi", new PageContext("/gioi-thieu", null), null,
                null, guestKey, "203.0.113.7", AssistantChannel.PUBLIC);
    }

    private static AssistantAskCommand user(AssistantCaller caller, String conversationId, String message) {
        return new AssistantAskCommand(conversationId, message, "vi", new PageContext("/chuyen", null), null,
                caller, null, "10.0.0.5", AssistantChannel.APP);
    }

    @Test
    void withoutModelBeanTheTurnIsUnavailableAndNothingIsWritten() {
        assertThatThrownBy(() -> service(null).ask(guest(null, "Xin chào", GUEST_KEY)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);

        assertThat(conversations.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(rateKeys).isEmpty();
    }

    @Test
    void withFlagOffTheModelIsNotCalled() {
        properties.setEnabled(false);
        model.reply("Xin chào", 10, 2);

        assertThatThrownBy(() -> service(model).ask(guest(null, "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(model.calls()).isZero();
    }

    @Test
    void messageLongerThanTheLimitIsRejectedBeforeTheModel() {
        assertThatThrownBy(() -> service(model).ask(guest(null, "a".repeat(1001), GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_MESSAGE_TOO_LONG);
        assertThat(model.calls()).isZero();
        assertThat(rateKeys).isEmpty();
    }

    @Test
    void limitCountsCharactersNotUtf16Units() {
        model.reply("Được.", 10, 2);

        // 1.000 ký tự tiếng Việt dựng sẵn: đúng giới hạn thì qua
        AssistantReply reply = service(model).ask(guest(null, "ộ".repeat(1000), GUEST_KEY));

        assertThat(reply.reply()).isEqualTo("Được.");
    }

    @Test
    void blankMessageIsInvalid() {
        assertThatThrownBy(() -> service(model).ask(guest(null, "   ", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void guestWithoutKeyIsInvalid() {
        assertThatThrownBy(() -> service(model).ask(guest(null, "Xin chào", " ")))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void answerIsSavedWithTokensAndCountedInTheBudget() {
        model.reply("Xin chào", 1200, 30);

        AssistantReply reply = service(model).ask(guest(null, "  Chào Lumo  ", GUEST_KEY));

        assertThat(reply.reply()).isEqualTo("Xin chào");
        assertThat(reply.conversationId()).matches("^c_[A-Za-z0-9_-]{22}$");
        assertThat(reply.usage().inputTokens()).isEqualTo(1200);
        assertThat(reply.usage().outputTokens()).isEqualTo(30);
        assertThat(reply.uiActions()).isEmpty();
        assertThat(reply.links()).isEmpty();
        assertThat(reply.handoff().offered()).isFalse();
        assertThat(budget.recorded).containsExactly(1230L);
        assertThat(rateKeys).containsExactly("ip:203.0.113.7@20");

        AssistantConversation conversation = conversations.findByPublicId(reply.conversationId()).orElseThrow();
        assertThat(conversation.getUserId()).isNull();
        assertThat(conversation.getGuestKey()).isEqualTo(GUEST_KEY);
        assertThat(conversation.getChannel()).isEqualTo(AssistantChannel.PUBLIC);
        List<AssistantMessage> saved = messages.findByConversationIdOrderByIdAsc(conversation.getId());
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRole()).isEqualTo(AssistantMessageRole.USER);
        assertThat(saved.get(0).getContent()).isEqualTo("Chào Lumo");
        assertThat(saved.get(1).getRole()).isEqualTo(AssistantMessageRole.ASSISTANT);
        assertThat(saved.get(1).getContent()).isEqualTo("Xin chào");
        assertThat(saved.get(1).getInputTokens()).isEqualTo(1200);
        assertThat(saved.get(1).getOutputTokens()).isEqualTo(30);
        assertThat(saved.get(1).getModel()).isEqualTo(FakeChatModel.MODEL);
    }

    @Test
    void promptIsSystemThenUserTurnWithContextAndTheQuestion() {
        model.reply("Được.", 10, 2);

        service(model).ask(user(DISPATCHER, null, "Phương án lỗi thời là gì?"));

        List<Message> sent = model.prompts().getFirst().getInstructions();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(sent.get(0).getText()).isEqualTo(promptBuilder.systemText(AssistantChannel.APP));
        assertThat(sent.get(1).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(sent.get(1).getText())
                .startsWith("<NGỮ CẢNH LƯỢT NÀY>")
                .contains("- Vai trò: Điều phối viên")
                .endsWith("</NGỮ CẢNH LƯỢT NÀY>\n\nPhương án lỗi thời là gì?");
        assertThat(rateKeys).containsExactly("user:8@60");
    }

    @Test
    void modelSeesOnlyTheLatestTwelveMessagesAsPlainText() {
        AssistantConversation conversation = existingConversation(8L, null, AssistantChannel.APP);
        for (int i = 0; i < 14; i++) {
            AssistantMessageRole role = i % 2 == 0 ? AssistantMessageRole.USER : AssistantMessageRole.ASSISTANT;
            messages.save(AssistantMessage.builder().conversationId(conversation.getId()).role(role)
                    .content("tin " + i).createdAt(T0.plusSeconds(i)).build());
        }
        model.reply("Đây.", 10, 2);

        AssistantReply reply = service(model).ask(user(DISPATCHER, conversation.getPublicId(), "Câu mới"));

        List<Message> sent = model.prompts().getFirst().getInstructions();
        assertThat(sent).hasSize(14);
        List<Message> history = sent.subList(1, 13);
        assertThat(history).extracting(Message::getText)
                .containsExactly("tin 2", "tin 3", "tin 4", "tin 5", "tin 6", "tin 7", "tin 8", "tin 9", "tin 10",
                        "tin 11", "tin 12", "tin 13");
        assertThat(history.getFirst().getMessageType()).isEqualTo(MessageType.USER);
        assertThat(history.getLast().getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(reply.conversationId()).isEqualTo(conversation.getPublicId());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.getId())).hasSize(16);
    }

    @Test
    void anotherUsersConversationIsNotFound() {
        AssistantConversation theirs = existingConversation(7L, null, AssistantChannel.APP);
        model.reply("Không được gọi", 1, 1);

        assertThatThrownBy(() -> service(model).ask(user(DISPATCHER, theirs.getPublicId(), "Xin chào")))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(model.calls()).isZero();
    }

    @Test
    void anotherGuestsConversationIsNotFound() {
        AssistantConversation theirs = existingConversation(null, "other-guest-key-0123", AssistantChannel.PUBLIC);
        model.reply("Không được gọi", 1, 1);

        assertThatThrownBy(() -> service(model).ask(guest(theirs.getPublicId(), "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(model.calls()).isZero();
    }

    @Test
    void userConversationIsNotOpenFromThePublicChannel() {
        AssistantConversation mine = existingConversation(8L, null, AssistantChannel.APP);

        assertThatThrownBy(() -> service(model).ask(guest(mine.getPublicId(), "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void unknownOrMalformedConversationIdIsNotFound() {
        assertThatThrownBy(() -> service(model).ask(guest("c_khongcotrongdb0001", "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service(model).ask(guest("' or 1=1 --", "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void refusalBecomesTheFixedSentenceInTheAskedLanguage() {
        model.reply("", "refusal", 50, 0).reply("partial", "REFUSAL", 50, 3);

        AssistantReply vi = service(model).ask(guest(null, "Câu bị từ chối", GUEST_KEY));
        AssistantReply en = service(model).ask(new AssistantAskCommand(null, "Refused", "en", null, null, null,
                GUEST_KEY, "203.0.113.7", AssistantChannel.PUBLIC));

        assertThat(vi.reply()).isEqualTo("Mình không thể giúp với yêu cầu này. Bạn có thể hỏi mình về cách dùng LoadMaster.");
        assertThat(en.reply()).isEqualTo("I can't help with that request. You can ask me about using LoadMaster.");
    }

    @Test
    void maxTokensWithoutTextIsUnavailableButTheTokensStillCount() {
        model.reply("   ", "max_tokens", 900, 800);

        assertThatThrownBy(() -> service(model).ask(guest(null, "Viết thật dài", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(budget.recorded).containsExactly(1700L);
        assertThat(conversations.count()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    void modelFailureIsUnavailableAndTheQuestionIsNotSaved() {
        model.fail(new RuntimeException("connect timed out"));

        assertThatThrownBy(() -> service(model).ask(guest(null, "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(conversations.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(budget.recorded).isEmpty();
    }

    @Test
    void exhaustedBudgetStopsTheTurnBeforeTheModel() {
        budget.exhausted = true;

        assertThatThrownBy(() -> service(model).ask(guest(null, "Xin chào", GUEST_KEY)))
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_BUDGET_EXCEEDED);
        assertThat(model.calls()).isZero();
    }

    @Test
    void secondTurnContinuesTheSameConversation() {
        model.reply("Một.", 10, 2).reply("Hai.", 20, 3);
        AssistantService service = service(model);

        AssistantReply first = service.ask(guest(null, "Câu một", GUEST_KEY));
        clock.set(T0.plusSeconds(60));
        AssistantReply second = service.ask(guest(first.conversationId(), "Câu hai", GUEST_KEY));

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        AssistantConversation conversation = conversations.findByPublicId(first.conversationId()).orElseThrow();
        assertThat(conversation.getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.getId()))
                .extracting(AssistantMessage::getContent).containsExactly("Câu một", "Một.", "Câu hai", "Hai.");
        assertThat(model.prompts().get(1).getInstructions()).extracting(Message::getText)
                .contains("Câu một", "Một.");
    }

    @Test
    void dispatcherToolCallRunsAndIsSavedWithTheAnswer() {
        model.toolCall("get_trip", "{\"tripCode\":\"TRIP-1\"}", 900, 20).reply("TRIP-1 đã lập kế hoạch.", 1000, 15);

        AssistantReply reply = service(model).ask(user(DISPATCHER, null, "TRIP-1 thế nào?"));

        assertThat(reply.reply()).isEqualTo("TRIP-1 đã lập kế hoạch.");
        assertThat(tripTools.asked).containsExactly("TRIP-1");
        assertThat(offeredTools(model.prompts().getFirst())).containsExactly("get_trip");
        assertThat(budget.recorded).containsExactly(1935L);
        AssistantConversation conversation = conversations.findByPublicId(reply.conversationId()).orElseThrow();
        AssistantMessage answer = messages.findByConversationIdOrderByIdAsc(conversation.getId()).get(1);
        assertThat(answer.getInputTokens()).isEqualTo(1900);
        assertThat(answer.getOutputTokens()).isEqualTo(35);
        assertThat(toolCalls.findByMessageIdOrderByIdAsc(answer.getId())).singleElement().satisfies(row -> {
            assertThat(row.getToolName()).isEqualTo("get_trip");
            assertThat(row.getArguments()).isEqualTo(Map.of("tripCode", "TRIP-1"));
            assertThat(row.isOk()).isTrue();
            assertThat(row.getDurationMs()).isNotNegative();
            assertThat(row.getCreatedAt()).isEqualTo(T0);
        });
    }

    @Test
    void guestIsNotOfferedADispatcherTool() {
        model.reply("Mình chưa xem được chuyến khi bạn chưa đăng nhập.", 10, 2);

        service(model).ask(guest(null, "TRIP-1 thế nào?", GUEST_KEY));

        assertThat(offeredTools(model.prompts().getFirst())).isEmpty();
        assertThat(tripTools.asked).isEmpty();
        assertThat(toolCalls.count()).isZero();
    }

    @Test
    void handoffIsOfferedFirstThenCreatedOnceForTheConversation() {
        SupportTicketService tickets = mock(SupportTicketService.class);
        when(tickets.createTicket(any())).thenReturn(SupportTicketResponse.builder().ticketCode("TKT-0042").build());
        String args = "{\"summary\":\"Không duyệt được phương án TRIP-1\",\"category\":\"ALGORITHM_ERROR\"}";
        model.toolCall("request_human_support", args, 900, 20).reply("Bạn muốn mình chuyển cho nhân viên không?", 1000, 15)
                .toolCall("request_human_support", args, 900, 20).reply("Đã chuyển, mã TKT-0042.", 1000, 15)
                .toolCall("request_human_support", args, 900, 20).reply("Yêu cầu TKT-0042 đã có.", 1000, 15);
        AssistantService service = service(model, new HandoffTool(tickets, conversations, transactionManager));

        AssistantReply offered = service.ask(user(DISPATCHER, null, "Cho tôi gặp nhân viên"));
        endRequest();
        AssistantReply created = service.ask(user(DISPATCHER, offered.conversationId(), "Có, chuyển giúp tôi"));
        endRequest();
        AssistantReply again = service.ask(user(DISPATCHER, offered.conversationId(), "Chuyển lại đi"));
        endRequest();

        assertThat(offered.handoff()).isEqualTo(new Handoff(true, null));
        assertThat(created.handoff()).isEqualTo(new Handoff(false, "TKT-0042"));
        assertThat(again.handoff()).isEqualTo(new Handoff(false, "TKT-0042"));
        ArgumentCaptor<CreateSupportTicketRequest> request = ArgumentCaptor.forClass(CreateSupportTicketRequest.class);
        verify(tickets, times(1)).createTicket(request.capture());
        assertThat(request.getValue().category()).isEqualTo(TicketCategory.ALGORITHM_ERROR);
        assertThat(request.getValue().description())
                .contains("Người dùng: Cho tôi gặp nhân viên", "Lumo: Bạn muốn mình chuyển cho nhân viên không?",
                        "Người dùng: Có, chuyển giúp tôi");
        AssistantConversation conversation = conversations.findByPublicId(offered.conversationId()).orElseThrow();
        assertThat(conversation.getHandoffTicketCode()).isEqualTo("TKT-0042");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.getId()))
                .filteredOn(message -> message.getRole() == AssistantMessageRole.ASSISTANT)
                .extracting(AssistantMessage::isHandoffOffered).containsExactly(true, false, false);
    }

    @Test
    void suggestedLinksComeBackOnlyForScreensTheCallerOpens() {
        model.toolCall("suggest_link", "{\"target\":\"OPTIMIZATION_SETUP\",\"tripCode\":\"TRIP-1\"}", 900, 20)
                .toolCall("suggest_link", "{\"target\":\"BILLING\"}", 900, 20)
                .reply("Mở Thiết lập tối ưu của TRIP-1.", 1000, 15);

        AssistantReply reply = service(model, new LinkTool()).ask(user(DISPATCHER, null, "Chạy tối ưu TRIP-1 ở đâu?"));

        assertThat(reply.links())
                .containsExactly(new AssistantLink("Thiết lập tối ưu chuyến TRIP-1", "/chuyen/TRIP-1/toi-uu"));
        assertThat(reply.handoff().offered()).isFalse();
    }

    @Test
    void viewerCommandsAreCheckedAgainstThePlanOnScreenAndSavedWithTheAnswer() {
        LoadPlanQueryService loadPlans = mock(LoadPlanQueryService.class);
        List<PlacementDetailResponse> placements = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            placements.add(PlacementDetailResponse.builder().id((long) i).packageCode("PK-" + i).stepSequence(i)
                    .build());
        }
        when(loadPlans.getPlan(42L)).thenReturn(LoadPlanDetailResponse.builder().id(42L).placements(placements)
                .unplaced(List.of()).build());
        model.toolCall("select_package", "{\"packageCode\":\"pk-7\"}", 900, 20)
                .toolCall("set_camera_preset", "{\"preset\":\"bay-len\"}", 900, 20)
                .toolCall("set_step", "{\"step\":9999}", 900, 20)
                .toolCall("select_package", "{\"packageCode\":\"OTHER-1\"}", 900, 20)
                .toolCall("set_camera_preset", "{\"preset\":\"cua-sau\"}", 900, 20)
                .reply("Mình đã chọn PK-7 và đổi sang góc cửa sau.", 1000, 15);
        AssistantService service = service(model, new ViewerActionTools(loadPlans));
        AssistantAskCommand fromPlanner = new AssistantAskCommand(null, "PK-7 nằm đâu?", "vi",
                new PageContext("/chuyen/TRIP-1/phuong-an", null), new ViewerContext("TRIP-1", "42", null),
                DISPATCHER, null, "10.0.0.5", AssistantChannel.APP);

        AssistantReply reply = service.ask(fromPlanner);

        assertThat(reply.uiActions()).containsExactly(
                new UiAction("selectPackage", "PK-7", null, null, null, null),
                new UiAction("setCameraPreset", null, "cua-sau", null, null, null));
        verify(loadPlans, times(1)).getPlan(42L);
        AssistantConversation conversation = conversations.findByPublicId(reply.conversationId()).orElseThrow();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.getId()).get(1).getUiActions())
                .containsExactly(Map.of("type", "selectPackage", "packageCode", "PK-7"),
                        Map.of("type", "setCameraPreset", "preset", "cua-sau"));

        model.reply("Bạn mở khung 3D để mình chọn kiện nhé.", 10, 2);
        AssistantReply outside = service.ask(user(DISPATCHER, null, "Chọn PK-7"));
        assertThat(offeredTools(model.prompts().getLast())).containsExactly("get_trip");
        assertThat(outside.uiActions()).isEmpty();
    }

    @Test
    void realRegistryGivesGuestsOnlyPricingAndLinksAndPlatformRolesNoHandoff() {
        assertThat(toolNames(ToolScope.ofGuest())).containsExactlyInAnyOrder("get_pricing_plans", "suggest_link");
        for (UserRole role : List.of(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_MANAGER, UserRole.SYSTEM_SUPPORTER)) {
            assertThat(toolNames(ToolScope.of(role))).as(role.name()).doesNotContain("request_human_support")
                    .contains("suggest_link");
        }
        assertThat(toolNames(ToolScope.of(UserRole.DRIVER))).contains("request_human_support", "get_my_payroll");
        registry.toolsFor(ToolScope.of(UserRole.DISPATCHER), new ToolCallLog()).forEach(tool ->
                assertThat(tool.getToolDefinition().inputSchema()).as(tool.getToolDefinition().name())
                        .doesNotContain("ToolContext").doesNotContain("\"context\""));
    }

    private List<String> toolNames(ToolScope scope) {
        return registry.toolsFor(scope, new ToolCallLog()).stream().map(tool -> tool.getToolDefinition().name())
                .toList();
    }

    /** Mỗi lượt hỏi là một yêu cầu riêng: ghi xuống và đọc lại từ cơ sở dữ liệu ở lượt sau. */
    private void endRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    private static List<String> offeredTools(Prompt prompt) {
        return prompt.getOptions() instanceof ToolCallingChatOptions options
                ? options.getToolCallbacks().stream().map(tool -> tool.getToolDefinition().name()).toList()
                : List.of();
    }

    /** Công cụ giả của điều phối viên: ghi lại mã chuyến được hỏi. */
    public static final class TripTools implements AssistantTool {
        private final List<String> asked = new ArrayList<>();

        @Override
        public Set<UserRole> roles() {
            return Set.of(UserRole.DISPATCHER);
        }

        @Tool(name = "get_trip", description = "Một chuyến của công ty")
        public Map<String, Object> getTrip(@ToolParam(description = "Mã chuyến") String tripCode) {
            asked.add(tripCode);
            return Map.of("tripCode", tripCode, "status", "PLANNED");
        }
    }

    private AssistantConversation existingConversation(Long userId, String guestKey, AssistantChannel channel) {
        return conversations.save(AssistantConversation.builder()
                .publicId("c_" + (userId == null ? guestKey.substring(0, 12) : "user" + userId + "conv01"))
                .userId(userId).companyId(userId == null ? null : 1L).guestKey(guestKey).channel(channel)
                .createdAt(T0).updatedAt(T0).build());
    }

    private static final class RecordingBudget implements AssistantBudget {
        private final List<Long> recorded = new ArrayList<>();
        private boolean exhausted;

        @Override
        public void ensureAvailable() {
            if (exhausted) {
                throw new AppException(ErrorCode.ASSISTANT_BUDGET_EXCEEDED);
            }
        }

        @Override
        public void record(long tokens) {
            recorded.add(tokens);
        }

        @Override
        public long remaining() {
            return exhausted ? 0 : Long.MAX_VALUE;
        }
    }
}
