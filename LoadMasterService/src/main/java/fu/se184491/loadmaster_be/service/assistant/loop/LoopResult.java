package fu.se184491.loadmaster_be.service.assistant.loop;

import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;

/**
 * Kết quả vòng gọi mô hình. {@code text} có thể rỗng (mô hình dừng vì {@code max_tokens} trước khi viết chữ):
 * nơi gọi quyết định. {@code stopReason} là lý do dừng của Anthropic viết thường ({@code end_turn}, {@code refusal}…).
 */
public record LoopResult(String text, TokenUsage usage, String stopReason, String model) {
}
