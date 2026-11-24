package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.exception.AppException;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết quả công cụ gửi cho mô hình: luôn là JSON, không quá {@link #MAX_CHARS} ký tự (phương án 1.000 kiện không được đổ
 * nguyên vào mô hình). Danh sách bọc trong {@link ToolPage} thì bỏ bớt dòng cho vừa; đối tượng khác quá dài thành
 * {@code {"truncated":true,"partial":"…"}}. Lỗi nghiệp vụ là {@code {"error":"<mã>"}}, không bao giờ thông điệp ngoại lệ.
 */
public final class ToolResults {

    public static final int MAX_CHARS = 6000;
    public static final int MAX_ROWS = 20;
    /** Số dòng tối đa của một công cụ đọc màn danh sách ({@link #list}). */
    public static final int LIST_ROWS = 30;
    /** Bộ lọc mô hình đưa không đúng giá trị cho phép (trạng thái, ngày…). */
    public static final String INVALID_FILTER = "INVALID_FILTER";
    public static final String TOOL_FAILED = "TOOL_FAILED";

    /** Bộ chuyển kết quả của mọi công cụ: chuỗi giữ nguyên (đã là JSON, như {@link #error}), còn lại qua {@link #json}. */
    public static final ToolCallResultConverter CONVERTER = (result, returnType) -> json(result);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    public static String json(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            if (value instanceof String text) {
                return text.length() <= MAX_CHARS ? text : partial(text);
            }
            if (value instanceof ToolPage<?> page) {
                return fitPage(page);
            }
            String out = JSON.writeValueAsString(value);
            return out.length() <= MAX_CHARS ? out : partial(out);
        } catch (JacksonException e) {
            return error(TOOL_FAILED);
        }
    }

    /** Tối đa {@code limit} dòng đầu (≤ {@value #MAX_ROWS}; không dương là {@value #MAX_ROWS}); {@code total} là số dòng thật. */
    public static <T> ToolPage<T> page(List<T> rows, int limit) {
        List<T> all = rows == null ? List.of() : rows;
        int cap = limit <= 0 ? MAX_ROWS : Math.min(limit, MAX_ROWS);
        List<T> shown = List.copyOf(all.subList(0, Math.min(cap, all.size())));
        return new ToolPage<>(all.size(), shown.size(), shown, false);
    }

    /**
     * Dòng đầu của một màn danh sách (yêu cầu giao, kho kiện, đội xe, công ty…): tối đa {@value #LIST_ROWS} dòng;
     * {@code total} là số dòng thật của bộ lọc (thường đếm ở cơ sở dữ liệu, nên lớn hơn số dòng đã đọc). Quá
     * {@link #MAX_CHARS} thì {@link #json} vẫn bỏ bớt dòng cuối.
     */
    public static <T> ToolPage<T> list(List<T> rows, long total) {
        List<T> all = rows == null ? List.of() : rows;
        List<T> shown = List.copyOf(all.subList(0, Math.min(LIST_ROWS, all.size())));
        int count = (int) Math.min(Integer.MAX_VALUE, Math.max(total, all.size()));
        return new ToolPage<>(count, shown.size(), shown, false);
    }

    public static String error(AppException exception) {
        return error(exception.getErrorCode().name());
    }

    public static String error(String code) {
        return JSON.writeValueAsString(Map.of("error", code));
    }

    /** Bỏ dòng cuối tới khi vừa; số dòng đã gửi đổi theo. */
    private static String fitPage(ToolPage<?> page) {
        List<?> rows = page.rows() == null ? List.of() : page.rows();
        for (int kept = rows.size(); kept >= 0; kept--) {
            boolean cut = page.truncated() || kept < rows.size();
            String out = JSON.writeValueAsString(new ToolPage<>(page.total(), kept, rows.subList(0, kept), cut));
            if (out.length() <= MAX_CHARS) {
                return out;
            }
        }
        return partial(JSON.writeValueAsString(page));
    }

    /** Phần đầu dài nhất của chuỗi còn vừa trong một đối tượng JSON; tìm nhị phân vì ký tự thoát làm chuỗi dài ra. */
    private static String partial(String text) {
        int low = 0;
        int high = Math.min(text.length(), MAX_CHARS);
        String best = wrap(text, 0);
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String out = wrap(text, mid);
            if (out.length() <= MAX_CHARS) {
                best = out;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private static String wrap(String text, int length) {
        int end = length;
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("truncated", true);
        wrapper.put("partial", text.substring(0, end));
        return JSON.writeValueAsString(wrapper);
    }
}
