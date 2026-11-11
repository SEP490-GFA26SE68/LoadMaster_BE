package fu.se184491.loadmaster_be.dto.response.support;

import lombok.*;

import java.util.List;

/** Yêu cầu hỗ trợ kèm cả cuộc trao đổi, theo thứ tự thời gian. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupportTicketThreadResponse {
    private SupportTicketResponse ticket;
    private List<TicketReplyResponse> replies;
}
