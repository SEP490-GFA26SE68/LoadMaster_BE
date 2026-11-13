package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.support.CreateTicketReplyRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketThreadResponse;
import fu.se184491.loadmaster_be.service.support.SupportTicketReplyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cuộc trao đổi của yêu cầu hỗ trợ. Ai đọc, trả lời được do dịch vụ quyết như mọi việc của phiếu: người của công ty chỉ phiếu do chính
 * mình gửi, nhân sự nền tảng mọi phiếu.
 */
@RestController
@RequestMapping("/api/support-tickets/{id}/replies")
@RequiredArgsConstructor
public class SupportTicketReplyController {

    private final SupportTicketReplyService supportTicketReplyService;

    @GetMapping
    public ApiResponse<SupportTicketThreadResponse> getThread(@PathVariable Long id) {
        return ApiResponse.success("Support ticket thread retrieved successfully", supportTicketReplyService.getThread(id));
    }

    @PostMapping
    public ApiResponse<SupportTicketThreadResponse> reply(
            @PathVariable Long id,
            @Valid @RequestBody CreateTicketReplyRequest request
    ) {
        return ApiResponse.success("Support ticket replied successfully", supportTicketReplyService.reply(id, request.message()));
    }
}
