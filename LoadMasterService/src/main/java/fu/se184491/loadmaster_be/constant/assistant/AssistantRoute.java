package fu.se184491.loadmaster_be.constant.assistant;

import java.util.EnumSet;
import java.util.Set;

/**
 * Đường một câu hỏi của khách đã đi để có câu trả lời. Ba đường đầu do trang công khai báo
 * ({@code POST /api/assistant/public/route-events}); hai đường sau máy chủ tự đếm.
 */
public enum AssistantRoute {
    /** Câu chào, cảm ơn… trình duyệt tự trả lời. */
    SMALL_TALK,
    /** Câu soạn sẵn của trình duyệt. */
    PREPARED,
    /** Khách bấm "hỏi chi tiết" sau câu soạn sẵn. */
    ESCALATED,
    /** Câu trả lời của mô hình. */
    MODEL,
    /** Câu trả lời lấy lại từ bộ nhớ đệm, không gọi mô hình. */
    CACHED;

    /** Các đường trình duyệt được báo. */
    public static final Set<AssistantRoute> CLIENT_REPORTED = EnumSet.of(SMALL_TALK, PREPARED, ESCALATED);
}
