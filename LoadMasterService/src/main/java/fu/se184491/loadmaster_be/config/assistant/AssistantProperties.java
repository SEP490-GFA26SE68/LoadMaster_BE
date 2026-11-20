package fu.se184491.loadmaster_be.config.assistant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình trợ lý AI ({@code assistant.*}). Tắt mặc định: chưa bật cờ hoặc thiếu key thì mọi endpoint của trợ lý trả
 * {@code ASSISTANT_UNAVAILABLE} và ứng dụng vẫn khởi động.
 */
@Configuration
@ConfigurationProperties(prefix = "assistant")
@Getter
@Setter
public class AssistantProperties {
    private boolean enabled = false;
    private String model = "claude-haiku-5-5";
    private int maxOutputTokens = 800;
    /** max_tokens của một lượt đọc mô tả hàng cho form "Thử với hàng của bạn" (JSON nhiều loại kiện + giả định). */
    private int extractMaxOutputTokens = 1200;
    private int maxToolRounds = 5;
    /** Số tin gần nhất của hội thoại gửi kèm cho mô hình. */
    private int historyMessages = 12;
    /** Lượt hỏi mỗi giờ của một IP khách (trang công khai). */
    private int guestPerHour = 20;
    /** Lượt hỏi mỗi giờ của một người đã đăng nhập. */
    private int userPerHour = 60;
    /** Tổng token vào + ra mỗi ngày (giờ Việt Nam) của cả hệ thống. */
    private long dailyTokenBudget = 2_000_000L;
    private int maxMessageChars = 1000;
    /** Độ dài tối đa của nội dung dán vào ô bản nháp yêu cầu giao. */
    private int maxDraftChars = 4000;
    /** max_tokens của một lượt đọc bản nháp yêu cầu giao (JSON gồm ghi chú và danh sách hàng). */
    private int draftMaxOutputTokens = 1500;
    /** {@code disabled} hoặc {@code adaptive}. */
    private String thinking = "disabled";
    /** {@code low}, {@code medium} hoặc {@code high}. */
    private String effort = "low";
    /** Giới hạn thời gian của một lần gọi mô hình. */
    private int callTimeoutSeconds = 20;
    /** Giới hạn thời gian của cả một lượt trả lời (nhiều vòng công cụ). */
    private int turnTimeoutSeconds = 35;
}
