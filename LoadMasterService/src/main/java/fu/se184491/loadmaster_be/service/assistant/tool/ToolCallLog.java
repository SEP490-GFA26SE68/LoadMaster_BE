package fu.se184491.loadmaster_be.service.assistant.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Các lần gọi công cụ của một lượt, giữ trong bộ nhớ tới khi lượt được lưu: dòng {@code assistant_tool_calls} cần mã
 * của tin trợ lý, mà tin đó chỉ có sau khi mô hình trả lời. Lượt hỏng thì không lưu gì, như tin nhắn.
 * Không giữ kết quả công cụ.
 */
public final class ToolCallLog {

    /** Một lần gọi. {@code arguments} là tham số đã đọc từ JSON, {@code null} khi mô hình gửi chuỗi không đọc được. */
    public record Entry(String toolName, Map<String, Object> arguments, boolean ok, int durationMs, Instant at) {
    }

    private final List<Entry> entries = new ArrayList<>();

    void add(Entry entry) {
        entries.add(entry);
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<String> toolNames() {
        return entries.stream().map(Entry::toolName).toList();
    }
}
