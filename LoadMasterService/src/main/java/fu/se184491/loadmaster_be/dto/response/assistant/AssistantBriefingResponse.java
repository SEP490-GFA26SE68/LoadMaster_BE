package fu.se184491.loadmaster_be.dto.response.assistant;

import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;

import java.time.Instant;
import java.util.List;

/** Bản tin "Tóm tắt hôm nay": các gạch đầu dòng và liên kết do máy chủ dựng theo quyền của người hỏi. */
public record AssistantBriefingResponse(Instant generatedAt, List<String> bullets, List<AssistantLink> links) {

    public AssistantBriefingResponse {
        bullets = List.copyOf(bullets);
        links = List.copyOf(links);
    }
}
