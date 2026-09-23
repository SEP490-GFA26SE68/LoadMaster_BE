package fu.se184491.loadmaster_be.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic paged response wrapper — cùng cấp với {@link ApiResponse}.
 *
 * <pre>
 * {
 *   "success": true,
 *   "message": "...",
 *   "data": [...],
 *   "page": 0,
 *   "size": 20,
 *   "totalElements": 100,
 *   "totalPages": 5,
 *   "last": false
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private boolean success;
    private String message;
    private List<T> data;

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;

    // ── Factory methods ───────────────────────────────────────────────────────

    public static <T> PageResponse<T> of(
            org.springframework.data.domain.Page<T> springPage) {
        return PageResponse.<T>builder()
                .success(true)
                .data(springPage.getContent())
                .page(springPage.getNumber())
                .size(springPage.getSize())
                .totalElements(springPage.getTotalElements())
                .totalPages(springPage.getTotalPages())
                .last(springPage.isLast())
                .build();
    }

    public static <T> PageResponse<T> of(
            String message,
            org.springframework.data.domain.Page<T> springPage) {
        return PageResponse.<T>builder()
                .success(true)
                .message(message)
                .data(springPage.getContent())
                .page(springPage.getNumber())
                .size(springPage.getSize())
                .totalElements(springPage.getTotalElements())
                .totalPages(springPage.getTotalPages())
                .last(springPage.isLast())
                .build();
    }
}
