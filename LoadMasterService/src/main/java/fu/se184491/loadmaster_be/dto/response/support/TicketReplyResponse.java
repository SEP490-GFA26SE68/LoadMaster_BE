package fu.se184491.loadmaster_be.dto.response.support;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một lần trả lời. id vắng với lần trả lời cũ còn nằm trong resolutionNote (mảng JSON của bản trước); khi đó authorId và
 * authorRole là đúng chữ đã ghi trong mảng đó.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketReplyResponse {
    private Long id;
    private String authorId;
    private String authorName;
    private String authorRole;
    private String message;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
