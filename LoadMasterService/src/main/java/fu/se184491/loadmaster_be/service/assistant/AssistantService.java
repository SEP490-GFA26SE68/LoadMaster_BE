package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantConversationResponse;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.loop.AssistantLoop;
import fu.se184491.loadmaster_be.service.assistant.loop.LoopResult;
import fu.se184491.loadmaster_be.service.assistant.loop.TokenMeter;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.model.Handoff;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Một lượt hỏi: cờ → độ dài → giới hạn lượt → ngân sách → hội thoại → mô hình → lưu. Mô hình được gọi ngoài giao dịch;
 * hội thoại mới chỉ được lưu khi mô hình trả lời được, nên lượt lỗi không để lại gì.
 */
@Service
public class AssistantService {

    private static final Pattern CONVERSATION_ID = Pattern.compile("^c_[A-Za-z0-9_-]{8,38}$");

    private final ObjectProvider<ChatModel> chatModels;
    private final AssistantProperties properties;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBudget budget;
    private final AssistantPromptBuilder promptBuilder;
    private final AssistantLoop loop;
    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AssistantService(@Qualifier(AssistantModelConfig.MODEL_BEAN) ObjectProvider<ChatModel> chatModels,
                            AssistantProperties properties, AssistantRateLimiter rateLimiter, AssistantBudget budget,
                            AssistantPromptBuilder promptBuilder, AssistantLoop loop,
                            AssistantConversationRepository conversations, AssistantMessageRepository messages,
                            PlatformTransactionManager transactionManager) {
        this(chatModels, properties, rateLimiter, budget, promptBuilder, loop, conversations, messages,
                transactionManager, Clock.systemUTC());
    }

    AssistantService(ObjectProvider<ChatModel> chatModels, AssistantProperties properties,
                     AssistantRateLimiter rateLimiter, AssistantBudget budget, AssistantPromptBuilder promptBuilder,
                     AssistantLoop loop, AssistantConversationRepository conversations,
                     AssistantMessageRepository messages, PlatformTransactionManager transactionManager, Clock clock) {
        this.chatModels = chatModels;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.promptBuilder = promptBuilder;
        this.loop = loop;
        this.conversations = conversations;
        this.messages = messages;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public AssistantReply ask(AssistantAskCommand command) {
        ChatModel model = properties.isEnabled() ? chatModels.getIfAvailable() : null;
        if (model == null) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }
        String question = command.message() == null ? "" : command.message().strip();
        if (question.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        if (question.codePointCount(0, question.length()) > properties.getMaxMessageChars()) {
            throw new AppException(ErrorCode.ASSISTANT_MESSAGE_TOO_LONG);
        }
        AssistantCaller caller = command.caller();
        if (caller == null && !StringUtils.hasText(command.guestKey())) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        if (caller != null) {
            rateLimiter.check("user:" + caller.userId(), properties.getUserPerHour());
        } else {
            rateLimiter.check("ip:" + Objects.requireNonNullElse(command.clientIp(), "unknown"),
                    properties.getGuestPerHour());
        }
        budget.ensureAvailable();

        AssistantConversation conversation = ownedConversation(command);
        String locale = AssistantPromptBuilder.normalizeLocale(command.locale());
        Instant askedAt = clock.instant();
        Prompt prompt = new Prompt(promptMessages(command, conversation, question, locale, askedAt));

        // Token của mọi lần gọi mô hình tính vào hạn mức, kể cả khi lượt hỏng ở vòng sau
        TokenMeter meter = new TokenMeter();
        LoopResult result;
        try {
            result = loop.run(model, prompt, List.of(), locale, meter);
        } finally {
            long spent = meter.total().total();
            if (spent > 0) {
                budget.record(spent);
            }
        }
        if (result.text().isBlank()) {
            throw new AppException(ErrorCode.ASSISTANT_UNAVAILABLE);
        }

        String modelName = StringUtils.hasText(result.model()) ? result.model() : properties.getModel();
        AssistantConversation saved = transactions.execute(status ->
                save(command, conversation, question, result, modelName, askedAt));
        return new AssistantReply(Objects.requireNonNull(saved).getPublicId(), result.text(), List.of(), List.of(),
                Handoff.none(), result.usage());
    }

    /** Đọc lại một hội thoại trong ứng dụng của chính người gọi; của người khác, của khách hay mã sai dạng thì như không có. */
    public AssistantConversationResponse conversation(String publicId, AssistantCaller caller) {
        if (publicId == null || !CONVERSATION_ID.matcher(publicId).matches()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AssistantConversation conversation = conversations.findByPublicId(publicId)
                .filter(found -> found.getChannel() == AssistantChannel.APP
                        && Objects.equals(found.getUserId(), caller.userId()))
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        List<AssistantConversationResponse.Item> items = messages
                .findByConversationIdOrderByIdAsc(conversation.getId()).stream()
                .map(message -> new AssistantConversationResponse.Item(
                        message.getRole().name().toLowerCase(Locale.ROOT), message.getContent(),
                        message.getCreatedAt()))
                .toList();
        return new AssistantConversationResponse(conversation.getPublicId(), items);
    }

    /** Hội thoại cũ của đúng người hỏi, hoặc {@code null} khi mở hội thoại mới. Của người khác thì như không có. */
    private AssistantConversation ownedConversation(AssistantAskCommand command) {
        String id = command.conversationId();
        if (!StringUtils.hasText(id)) {
            return null;
        }
        if (!CONVERSATION_ID.matcher(id).matches()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AssistantConversation conversation = conversations.findByPublicId(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!isOwner(conversation, command)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return conversation;
    }

    private static boolean isOwner(AssistantConversation conversation, AssistantAskCommand command) {
        if (conversation.getChannel() != command.channel()) {
            return false;
        }
        AssistantCaller caller = command.caller();
        if (caller != null) {
            return Objects.equals(conversation.getUserId(), caller.userId());
        }
        return conversation.getUserId() == null && Objects.equals(conversation.getGuestKey(), command.guestKey());
    }

    private List<Message> promptMessages(AssistantAskCommand command, AssistantConversation conversation,
                                         String question, String locale, Instant askedAt) {
        List<Message> out = new ArrayList<>();
        out.add(new SystemMessage(promptBuilder.systemText(command.channel())));
        if (conversation != null) {
            out.addAll(history(conversation.getId()));
        }
        String context = promptBuilder.turnContext(command.caller(), command.page(), command.viewer(), locale, askedAt);
        out.add(new UserMessage(context + "\n\n" + question));
        return out;
    }

    /** Các tin gần nhất, cũ trước, chỉ chữ. Lượt đầu phải là của người dùng nên bỏ tin trợ lý đứng đầu. */
    private List<Message> history(Long conversationId) {
        List<AssistantMessage> latest = new ArrayList<>(messages.findByConversationIdOrderByIdDesc(
                conversationId, PageRequest.of(0, properties.getHistoryMessages())));
        java.util.Collections.reverse(latest);
        while (!latest.isEmpty() && latest.getFirst().getRole() != AssistantMessageRole.USER) {
            latest.removeFirst();
        }
        List<Message> out = new ArrayList<>(latest.size());
        for (AssistantMessage message : latest) {
            out.add(message.getRole() == AssistantMessageRole.USER
                    ? new UserMessage(message.getContent())
                    : new org.springframework.ai.chat.messages.AssistantMessage(message.getContent()));
        }
        return out;
    }

    private AssistantConversation save(AssistantAskCommand command, AssistantConversation existing, String question,
                                       LoopResult result, String modelName, Instant askedAt) {
        Instant answeredAt = clock.instant();
        AssistantConversation conversation = existing != null ? existing : newConversation(command, askedAt);
        conversation.setUpdatedAt(answeredAt);
        conversation = conversations.save(conversation);
        messages.save(AssistantMessage.builder()
                .conversationId(conversation.getId()).role(AssistantMessageRole.USER).content(question)
                .createdAt(askedAt).build());
        messages.save(AssistantMessage.builder()
                .conversationId(conversation.getId()).role(AssistantMessageRole.ASSISTANT).content(result.text())
                .inputTokens(result.usage().inputTokens()).outputTokens(result.usage().outputTokens())
                .model(modelName).createdAt(answeredAt).build());
        return conversation;
    }

    private AssistantConversation newConversation(AssistantAskCommand command, Instant now) {
        AssistantCaller caller = command.caller();
        return AssistantConversation.builder()
                .publicId(newPublicId())
                .userId(caller == null ? null : caller.userId())
                .companyId(caller == null ? null : caller.companyId())
                .guestKey(caller == null ? command.guestKey() : null)
                .channel(command.channel())
                .createdAt(now).updatedAt(now)
                .build();
    }

    private String newPublicId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "c_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
