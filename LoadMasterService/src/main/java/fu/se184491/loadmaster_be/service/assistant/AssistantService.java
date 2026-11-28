package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import fu.se184491.loadmaster_be.constant.assistant.AssistantRoute;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantConversationResponse;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.assistant.AssistantConversationRepository;
import fu.se184491.loadmaster_be.repository.assistant.AssistantMessageRepository;
import fu.se184491.loadmaster_be.service.assistant.cache.GuestReplyCache;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantBudget;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.loop.AssistantLoop;
import fu.se184491.loadmaster_be.service.assistant.loop.LoopResult;
import fu.se184491.loadmaster_be.service.assistant.loop.TokenMeter;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.model.Handoff;
import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;
import fu.se184491.loadmaster_be.service.assistant.model.UiAction;
import fu.se184491.loadmaster_be.service.assistant.model.ViewerContext;
import fu.se184491.loadmaster_be.service.assistant.output.UiActionValidator;
import fu.se184491.loadmaster_be.service.assistant.prompt.AssistantPromptBuilder;
import fu.se184491.loadmaster_be.service.assistant.stats.RouteStatsService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantToolRegistry;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTurn;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolCallLog;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Một lượt hỏi: cờ → độ dài → giới hạn lượt → ngân sách → hội thoại → mô hình → lưu. Mô hình được gọi ngoài giao dịch;
 * hội thoại mới chỉ được lưu khi mô hình trả lời được, nên lượt lỗi không để lại gì.
 *
 * <p>Lượt đầu của khách ở trang công khai đi qua {@link GuestReplyCache} sau giới hạn lượt: trúng đệm thì không chạm
 * ngân sách, không gọi mô hình, vẫn mở hội thoại mới và lưu cặp hỏi / đáp. Lượt khách nào cũng được đếm vào
 * {@link RouteStatsService} ({@code MODEL} hoặc {@code CACHED}), không kèm gì nhận diện người hỏi.
 */
@Service
public class AssistantService {

    private static final Pattern CONVERSATION_ID = Pattern.compile("^c_[A-Za-z0-9_-]{8,38}$");
    private static final Pattern PLAN_ID = Pattern.compile("^[0-9]{1,19}$");
    /** Lý do dừng của lượt trúng đệm, chỉ dùng trong bộ nhớ. */
    private static final String CACHED_STOP = "cached";
    private static final Set<String> UNCACHEABLE_STOPS = Set.of("refusal", "max_tokens");
    /** Công cụ nói rõ chủ đề (mã chủ đề trùng với bộ câu soạn sẵn của trang công khai). */
    private static final Map<String, String> TOOL_TOPICS = Map.of("get_pricing_plans", "plans");

    private final ObjectProvider<ChatModel> chatModels;
    private final AssistantProperties properties;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantBudget budget;
    private final AssistantPromptBuilder promptBuilder;
    private final AssistantLoop loop;
    private final AssistantToolRegistry tools;
    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final TransactionTemplate transactions;
    private final Clock clock;
    /** {@code null}: không dùng đệm (test dựng tay). */
    private final GuestReplyCache replyCache;
    /** {@code null}: không đếm (test dựng tay). */
    private final RouteStatsService routeStats;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AssistantService(@Qualifier(AssistantModelConfig.MODEL_BEAN) ObjectProvider<ChatModel> chatModels,
                            AssistantProperties properties, AssistantRateLimiter rateLimiter, AssistantBudget budget,
                            AssistantPromptBuilder promptBuilder, AssistantLoop loop, AssistantToolRegistry tools,
                            AssistantConversationRepository conversations, AssistantMessageRepository messages,
                            PlatformTransactionManager transactionManager, GuestReplyCache replyCache,
                            RouteStatsService routeStats) {
        this(chatModels, properties, rateLimiter, budget, promptBuilder, loop, tools, conversations, messages,
                transactionManager, Clock.systemUTC(), replyCache, routeStats);
    }

    AssistantService(ObjectProvider<ChatModel> chatModels, AssistantProperties properties,
                     AssistantRateLimiter rateLimiter, AssistantBudget budget, AssistantPromptBuilder promptBuilder,
                     AssistantLoop loop, AssistantToolRegistry tools, AssistantConversationRepository conversations,
                     AssistantMessageRepository messages, PlatformTransactionManager transactionManager, Clock clock) {
        this(chatModels, properties, rateLimiter, budget, promptBuilder, loop, tools, conversations, messages,
                transactionManager, clock, null, null);
    }

    AssistantService(ObjectProvider<ChatModel> chatModels, AssistantProperties properties,
                     AssistantRateLimiter rateLimiter, AssistantBudget budget, AssistantPromptBuilder promptBuilder,
                     AssistantLoop loop, AssistantToolRegistry tools, AssistantConversationRepository conversations,
                     AssistantMessageRepository messages, PlatformTransactionManager transactionManager, Clock clock,
                     GuestReplyCache replyCache, RouteStatsService routeStats) {
        this.replyCache = replyCache;
        this.routeStats = routeStats;
        this.chatModels = chatModels;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.promptBuilder = promptBuilder;
        this.loop = loop;
        this.tools = tools;
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
        String locale = AssistantPromptBuilder.normalizeLocale(command.locale());
        boolean guestTurn = caller == null && command.channel() == AssistantChannel.PUBLIC;
        Optional<String> cacheKey = replyCache == null ? Optional.empty()
                : replyCache.keyFor(command, question, locale);
        if (cacheKey.isPresent()) {
            Optional<GuestReplyCache.Entry> hit = replyCache.get(cacheKey.get());
            if (hit.isPresent()) {
                return replay(command, question, locale, hit.get());
            }
        }
        budget.ensureAvailable();

        AssistantConversation conversation = ownedConversation(command);
        Instant askedAt = clock.instant();
        List<AssistantMessage> latest = latestMessages(conversation);
        Prompt prompt = new Prompt(promptMessages(command, latest, question, locale, askedAt));
        AssistantTurn turn = turnOf(caller, conversation, latest, question, locale, command.viewer());

        // Token của mọi lần gọi mô hình tính vào hạn mức, kể cả khi lượt hỏng ở vòng sau
        ToolCallLog toolLog = new ToolCallLog();
        List<ToolCallback> callbacks = tools.toolsFor(turn, toolLog);
        TokenMeter meter = new TokenMeter();
        LoopResult result;
        try {
            result = loop.run(model, prompt, callbacks, locale, meter);
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
        // Lọc lần cuối theo whitelist trên đúng phương án công cụ đã đọc; không đọc được phương án thì không có lệnh nào
        List<UiAction> uiActions = UiActionValidator.filter(turn.uiActions(), turn.loadedViewerPlan().orElse(null));
        AssistantConversation saved = transactions.execute(status ->
                save(command, conversation, question, result, modelName, askedAt, toolLog, turn, uiActions));
        String topicId = guestTurn ? topicOf(toolLog) : null;
        if (guestTurn && routeStats != null) {
            routeStats.record(AssistantRoute.MODEL, topicId, locale);
        }
        if (cacheKey.isPresent() && cacheable(result, turn, toolLog, uiActions)) {
            replyCache.put(cacheKey.get(), new GuestReplyCache.Entry(result.text(), turn.links(), modelName, topicId));
        }
        return new AssistantReply(Objects.requireNonNull(saved).getPublicId(), result.text(), uiActions, turn.links(),
                new Handoff(turn.handoffOffered(), turn.reportedTicketCode()), result.usage());
    }

    /**
     * Lượt trúng đệm: không ngân sách, không mô hình. Vẫn mở hội thoại mới và lưu cặp hỏi / đáp (token 0) để câu hỏi tiếp
     * theo có ngữ cảnh như lượt gốc.
     */
    private AssistantReply replay(AssistantAskCommand command, String question, String locale,
                                  GuestReplyCache.Entry cached) {
        Instant askedAt = clock.instant();
        LoopResult result = new LoopResult(cached.text(), TokenUsage.ZERO, CACHED_STOP, cached.model());
        AssistantTurn turn = new AssistantTurn(ToolScope.ofGuest(), locale, null, false, null, List.of());
        AssistantConversation saved = transactions.execute(status -> save(command, null, question, result,
                cached.model(), askedAt, new ToolCallLog(), turn, List.of()));
        if (routeStats != null) {
            routeStats.record(AssistantRoute.CACHED, cached.topicId(), locale);
        }
        return new AssistantReply(Objects.requireNonNull(saved).getPublicId(), cached.text(), List.of(),
                cached.links(), Handoff.none(), TokenUsage.ZERO);
    }

    /**
     * Chỉ đệm câu trả lời trọn vẹn của lượt bình thường: không từ chối, không bị cắt vì {@code max_tokens}, không mời
     * chuyển nhân viên hay tạo yêu cầu hỗ trợ, không lệnh khung 3D, không công cụ nào lỗi.
     */
    private static boolean cacheable(LoopResult result, AssistantTurn turn, ToolCallLog toolLog,
                                     List<UiAction> uiActions) {
        return StringUtils.hasText(result.text()) && !UNCACHEABLE_STOPS.contains(result.stopReason())
                && !turn.handoffOffered() && turn.reportedTicketCode() == null && uiActions.isEmpty()
                && toolLog.entries().stream().allMatch(ToolCallLog.Entry::ok);
    }

    /** Chủ đề suy được không tốn gì: từ công cụ mô hình đã gọi. Không có thì {@code null}. */
    private static String topicOf(ToolCallLog toolLog) {
        for (String name : toolLog.toolNames()) {
            String topic = TOOL_TOPICS.get(name);
            if (topic != null) {
                return topic;
            }
        }
        return null;
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

    private List<Message> promptMessages(AssistantAskCommand command, List<AssistantMessage> latest,
                                         String question, String locale, Instant askedAt) {
        List<Message> out = new ArrayList<>();
        out.add(new SystemMessage(promptBuilder.systemText(command.channel())));
        out.addAll(history(latest));
        String context = promptBuilder.turnContext(command.caller(), command.page(), command.viewer(), locale, askedAt);
        out.add(new UserMessage(context + "\n\n" + question));
        return out;
    }

    /** Các tin gần nhất của hội thoại, cũ trước; hội thoại mới thì rỗng. */
    private List<AssistantMessage> latestMessages(AssistantConversation conversation) {
        if (conversation == null) {
            return List.of();
        }
        List<AssistantMessage> latest = new ArrayList<>(messages.findByConversationIdOrderByIdDesc(
                conversation.getId(), PageRequest.of(0, properties.getHistoryMessages())));
        Collections.reverse(latest);
        return latest;
    }

    /**
     * Lượt hỏi cho công cụ hành động. Lời mời chuyển nhân viên chỉ tính khi tin trợ lý liền trước mang
     * {@code handoff_offered}; các tin gần nhất cộng câu hỏi này đi vào mô tả của yêu cầu hỗ trợ.
     */
    private static AssistantTurn turnOf(AssistantCaller caller, AssistantConversation conversation,
                                        List<AssistantMessage> latest, String question, String locale,
                                        ViewerContext viewer) {
        AssistantMessage previous = latest.isEmpty() ? null : latest.getLast();
        boolean invited = previous != null && previous.getRole() == AssistantMessageRole.ASSISTANT
                && previous.isHandoffOffered();
        List<AssistantTurn.Line> recent = new ArrayList<>(latest.size() + 1);
        for (AssistantMessage message : latest) {
            recent.add(new AssistantTurn.Line(message.getRole() == AssistantMessageRole.USER, message.getContent()));
        }
        recent.add(new AssistantTurn.Line(true, question));
        Long viewerPlanId = caller == null ? null : viewerPlanId(viewer);
        ToolScope scope = viewerPlanId == null ? scopeOf(caller) : scopeOf(caller).withViewer();
        return new AssistantTurn(scope, locale, conversation == null ? null : conversation.getId(), invited,
                conversation == null ? null : conversation.getHandoffTicketCode(), recent, viewerPlanId);
    }

    /** Mã phương án khung 3D gửi kèm (chuỗi số); sai dạng thì như không có. */
    private static Long viewerPlanId(ViewerContext viewer) {
        if (viewer == null || viewer.loadPlanId() == null || !PLAN_ID.matcher(viewer.loadPlanId()).matches()) {
            return null;
        }
        try {
            return Long.valueOf(viewer.loadPlanId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Các tin gần nhất cho mô hình, chỉ chữ. Lượt đầu phải là của người dùng nên bỏ tin trợ lý đứng đầu. */
    private static List<Message> history(List<AssistantMessage> messagesOldestFirst) {
        List<AssistantMessage> latest = new ArrayList<>(messagesOldestFirst);
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
                                       LoopResult result, String modelName, Instant askedAt,
                                       ToolCallLog toolLog, AssistantTurn turn, List<UiAction> uiActions) {
        Instant answeredAt = clock.instant();
        AssistantConversation conversation = existing != null ? existing : newConversation(command, askedAt);
        conversation.setUpdatedAt(answeredAt);
        conversation = conversations.save(conversation);
        messages.save(AssistantMessage.builder()
                .conversationId(conversation.getId()).role(AssistantMessageRole.USER).content(question)
                .createdAt(askedAt).build());
        AssistantMessage answer = messages.save(AssistantMessage.builder()
                .conversationId(conversation.getId()).role(AssistantMessageRole.ASSISTANT).content(result.text())
                .inputTokens(result.usage().inputTokens()).outputTokens(result.usage().outputTokens())
                .handoffOffered(turn.handoffOffered()).uiActions(UiActionValidator.toMaps(uiActions))
                .model(modelName).createdAt(answeredAt).build());
        tools.save(toolLog, answer.getId());
        return conversation;
    }

    private static ToolScope scopeOf(AssistantCaller caller) {
        return caller == null || caller.role() == null ? ToolScope.ofGuest() : ToolScope.of(caller.role());
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
