package fu.se184491.loadmaster_be.service.assistant.model;

import java.util.List;

/** Câu trả lời của một lượt. */
public record AssistantReply(
        String conversationId,
        String reply,
        List<UiAction> uiActions,
        List<AssistantLink> links,
        Handoff handoff,
        TokenUsage usage) {
}
