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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @MockitoBean private JwtDecoder jwtDecoder;

    private final FakeChatModel model = new FakeChatModel();
    private final AssistantProperties properties = new AssistantProperties();
    private final RecordingBudget budget = new RecordingBudget();
    private final List<String> rateKeys = new ArrayList<>();
    private final MutableClock clock = new MutableClock(T0);

    @BeforeEach
    void enable() {
        properties.setEnabled(true);
    }

    private AssistantService service(ChatModel chatModel) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                chatModel == null ? Map.<String, Object>of() : Map.<String, Object>of("assistantChatModel", chatModel));
        ObjectProvider<ChatModel> provider = beans.getBeanProvider(ChatModel.class);
        AssistantRateLimiter limiter = (key, limit) -> rateKeys.add(key + "@" + limit);
        return new AssistantService(provider, properties, limiter, budget, promptBuilder, loop, conversations,
                messages, transactionManager, clock);
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
