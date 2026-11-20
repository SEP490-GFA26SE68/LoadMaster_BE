package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.assistant.AssistantPublicMessageRequest;
import fu.se184491.loadmaster_be.service.assistant.AssistantService;
import fu.se184491.loadmaster_be.service.assistant.gate.ClientIpResolver;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trợ lý của trang công khai: không cần token, không đọc dữ liệu người dùng. Người đã đăng nhập gọi vào đây vẫn là khách.
 *
 * <p>POST /api/assistant/public/messages
 */
@RestController
@RequestMapping("/api/assistant/public")
@RequiredArgsConstructor
public class AssistantPublicController {

    private final AssistantService assistantService;
    private final ClientIpResolver clientIpResolver;

    // POST /api/assistant/public/messages
    @PostMapping("/messages")
    public ApiResponse<AssistantReply> ask(@Valid @RequestBody AssistantPublicMessageRequest request,
                                           HttpServletRequest http) {
        AssistantAskCommand command = new AssistantAskCommand(request.conversationId(), request.message(),
                request.locale(), null, null, null, request.guestKey(), clientIpResolver.resolve(http),
                AssistantChannel.PUBLIC);
        return ApiResponse.success(assistantService.ask(command));
    }
}
