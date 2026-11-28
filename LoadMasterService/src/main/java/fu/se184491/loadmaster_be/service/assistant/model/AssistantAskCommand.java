package fu.se184491.loadmaster_be.service.assistant.model;

import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;

/**
 * Một lượt hỏi. {@code caller} là {@code null} với khách; khi đó {@code guestKey} nhận diện hội thoại và
 * {@code clientIp} dùng để đếm lượt.
 */
public record AssistantAskCommand(
        String conversationId,
        String message,
        String locale,
        PageContext page,
        ViewerContext viewer,
        AssistantCaller caller,
        String guestKey,
        String clientIp,
        AssistantChannel channel) {
}
