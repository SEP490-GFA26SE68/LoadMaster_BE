package fu.se184491.loadmaster_be.service.assistant.tool;

import java.util.List;

/**
 * Danh sách trả cho mô hình: {@code total} là số dòng thật, {@code shown} là số dòng gửi đi (tối đa
 * {@link ToolResults#MAX_ROWS}). {@code truncated} bật khi còn phải bỏ bớt dòng cho vừa
 * {@link ToolResults#MAX_CHARS} ký tự.
 */
public record ToolPage<T>(int total, int shown, List<T> rows, boolean truncated) {
}
