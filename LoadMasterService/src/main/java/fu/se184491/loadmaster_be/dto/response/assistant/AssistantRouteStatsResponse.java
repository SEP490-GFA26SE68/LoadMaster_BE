package fu.se184491.loadmaster_be.dto.response.assistant;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tổng số lượt theo đường trả lời trong khoảng ngày (giờ Việt Nam, gồm cả hai đầu). {@code byRoute} luôn đủ năm đường;
 * {@code byTopic} xếp nhiều trước, {@code topicId} {@code null} là không rõ chủ đề. {@code partial}: không đọc được nơi
 * lưu bền, số chỉ gồm phần máy chủ này còn giữ trong bộ nhớ.
 */
public record AssistantRouteStatsResponse(
        LocalDate from,
        LocalDate to,
        long total,
        Map<String, Long> byRoute,
        List<Topic> byTopic,
        Map<String, Long> byLocale,
        boolean partial) {

    public record Topic(String topicId, long total, Map<String, Long> byRoute) {
    }
}
