package fu.se184491.loadmaster_be.service.assistant.gate;

/** Giới hạn số lượt hỏi trợ lý mỗi giờ theo khoá ({@code ip:…} cho khách, {@code user:…} cho người đăng nhập). */
public interface AssistantRateLimiter {

    /** Ghi một lượt của {@code key}; quá {@code limitPerHour} lượt trong giờ hiện tại thì ném {@code ASSISTANT_RATE_LIMITED}. */
    void check(String key, int limitPerHour);
}
