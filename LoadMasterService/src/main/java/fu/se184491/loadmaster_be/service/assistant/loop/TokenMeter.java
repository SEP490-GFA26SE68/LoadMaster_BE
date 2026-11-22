package fu.se184491.loadmaster_be.service.assistant.loop;

import fu.se184491.loadmaster_be.service.assistant.model.TokenUsage;

/**
 * Cộng token của mọi lần gọi mô hình trong một lượt, kể cả lượt hỏng giữa chừng (hết giờ, lỗi mạng ở vòng sau):
 * nơi gọi tính số này vào hạn mức ngày dù lượt có trả lời hay không. Một lượt một bản, chỉ dùng trên luồng của request.
 */
public final class TokenMeter {

    private TokenUsage total = TokenUsage.ZERO;

    void add(TokenUsage usage) {
        total = total.plus(usage);
    }

    public TokenUsage total() {
        return total;
    }
}
