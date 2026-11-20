package fu.se184491.loadmaster_be.service.assistant.gate;

/** Hạn mức token mỗi ngày (giờ Việt Nam) của cả hệ thống, tính token vào + ra. */
public interface AssistantBudget {

    /** Ném {@code ASSISTANT_BUDGET_EXCEEDED} khi hôm nay đã dùng hết hạn mức. Gọi trước khi gọi mô hình. */
    void ensureAvailable();

    /** Cộng số token đã gọi mô hình của một lượt, kể cả lượt hỏng giữa chừng. */
    void record(long tokens);

    /** Số token còn lại hôm nay, không âm. */
    long remaining();
}
