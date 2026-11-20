package fu.se184491.loadmaster_be.dto.response.assistant;

import java.time.Instant;
import java.util.List;

/** Một hội thoại trong ứng dụng, tin theo thứ tự gửi. {@code role} là {@code user} hoặc {@code assistant}. */
public record AssistantConversationResponse(String conversationId, List<Item> messages) {

    public record Item(String role, String content, Instant createdAt) {
    }
}
