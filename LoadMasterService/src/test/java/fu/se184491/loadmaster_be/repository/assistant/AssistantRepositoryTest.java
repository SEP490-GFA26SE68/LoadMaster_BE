package fu.se184491.loadmaster_be.repository.assistant;

import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantMessageRole;
import fu.se184491.loadmaster_be.entity.assistant.AssistantConversation;
import fu.se184491.loadmaster_be.entity.assistant.AssistantMessage;
import fu.se184491.loadmaster_be.entity.assistant.AssistantToolCall;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ba bảng của trợ lý trên H2: đọc hội thoại theo mã công khai, lịch sử mới nhất trước, tổng token theo mốc. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:assistant-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Transactional
class AssistantRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-20T01:00:00Z");

    @Autowired private AssistantConversationRepository conversations;
    @Autowired private AssistantMessageRepository messages;
    @Autowired private AssistantToolCallRepository toolCalls;
    @Autowired private EntityManager entityManager;

    @MockitoBean private JwtDecoder jwtDecoder;

    private AssistantConversation conversation(String publicId) {
        return conversations.save(AssistantConversation.builder()
                .publicId(publicId).guestKey("guest-key-0123456789").channel(AssistantChannel.PUBLIC)
                .createdAt(T0).updatedAt(T0).build());
    }

    private AssistantMessage message(Long conversationId, AssistantMessageRole role, String content,
                                     int in, int out, Instant at) {
        return messages.save(AssistantMessage.builder()
                .conversationId(conversationId).role(role).content(content)
                .inputTokens(in).outputTokens(out).createdAt(at).build());
    }

    @Test
    void conversationIsFoundByPublicIdAndHistoryComesNewestFirst() {
        AssistantConversation c = conversation("conv-1");
        message(c.getId(), AssistantMessageRole.USER, "Gói Pro bao nhiêu?", 0, 0, T0);
        AssistantMessage reply = messages.save(AssistantMessage.builder()
                .conversationId(c.getId()).role(AssistantMessageRole.ASSISTANT).content("Xem bảng giá.")
                .uiActions(List.of(Map.of("type", "select_package", "packageCode", "PK-1")))
                .handoffOffered(true).inputTokens(900).outputTokens(40).model("claude-haiku-5-5")
                .createdAt(T0.plusSeconds(2)).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(conversations.findByPublicId("conv-1")).get()
                .extracting(AssistantConversation::getId).isEqualTo(c.getId());
        assertThat(conversations.findByPublicId("conv-x")).isEmpty();

        List<AssistantMessage> newestFirst = messages.findByConversationIdOrderByIdDesc(c.getId(), PageRequest.of(0, 12));
        assertThat(newestFirst).extracting(AssistantMessage::getRole)
                .containsExactly(AssistantMessageRole.ASSISTANT, AssistantMessageRole.USER);
        assertThat(messages.findByConversationIdOrderByIdDesc(c.getId(), PageRequest.of(0, 1)))
                .extracting(AssistantMessage::getId).containsExactly(reply.getId());
        assertThat(messages.findByConversationIdOrderByIdAsc(c.getId()))
                .extracting(AssistantMessage::getContent).containsExactly("Gói Pro bao nhiêu?", "Xem bảng giá.");

        AssistantMessage stored = messages.findById(reply.getId()).orElseThrow();
        assertThat(stored.isHandoffOffered()).isTrue();
        assertThat(stored.getUiActions()).containsExactly(Map.of("type", "select_package", "packageCode", "PK-1"));
    }

    @Test
    void sumTokensSinceCountsOnlyMessagesFromTheMark() {
        AssistantConversation c = conversation("conv-2");
        message(c.getId(), AssistantMessageRole.ASSISTANT, "hôm qua", 1000, 100, T0.minusSeconds(60));
        message(c.getId(), AssistantMessageRole.USER, "câu hỏi", 0, 0, T0);
        message(c.getId(), AssistantMessageRole.ASSISTANT, "hôm nay", 700, 50, T0);
        message(c.getId(), AssistantMessageRole.ASSISTANT, "sau đó", 300, 25, T0.plusSeconds(60));
        entityManager.flush();

        assertThat(messages.sumTokensSince(T0)).isEqualTo(1075L);
        assertThat(messages.sumTokensSince(T0.plusSeconds(3600))).isZero();
    }

    @Test
    void toolCallKeepsNameArgumentsAndOutcomeOnly() {
        AssistantConversation c = conversation("conv-3");
        AssistantMessage reply = message(c.getId(), AssistantMessageRole.ASSISTANT, "Chuyến đang chạy.", 10, 5, T0);
        toolCalls.save(AssistantToolCall.builder()
                .messageId(reply.getId()).toolName("get_trip").arguments(Map.of("tripCode", "TRIP-10"))
                .ok(true).durationMs(42).createdAt(T0).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(toolCalls.findByMessageIdOrderByIdAsc(reply.getId())).singleElement().satisfies(call -> {
            assertThat(call.getToolName()).isEqualTo("get_trip");
            assertThat(call.getArguments()).isEqualTo(Map.of("tripCode", "TRIP-10"));
            assertThat(call.isOk()).isTrue();
            assertThat(call.getDurationMs()).isEqualTo(42);
        });
    }
}
