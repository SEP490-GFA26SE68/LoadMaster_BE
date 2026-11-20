package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.assistant.AssistantMessageRequest;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantBriefingResponse;
import fu.se184491.loadmaster_be.dto.response.assistant.AssistantConversationResponse;
import fu.se184491.loadmaster_be.service.assistant.AssistantCallerResolver;
import fu.se184491.loadmaster_be.service.assistant.AssistantService;
import fu.se184491.loadmaster_be.service.assistant.briefing.BriefingService;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.model.PageContext;
import fu.se184491.loadmaster_be.service.assistant.model.ViewerContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trợ lý trong ứng dụng, mọi vai trò đã đăng nhập. Người hỏi luôn đọc từ token; công ty bị khoá gói thì bộ lọc chặn
 * trước khi tới đây.
 *
 * <p>POST /api/assistant/messages · GET /api/assistant/conversations/{id} · GET /api/assistant/briefing
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantCallerResolver callerResolver;
    private final BriefingService briefingService;

    // POST /api/assistant/messages
    @PostMapping("/messages")
    public ApiResponse<AssistantReply> ask(@Valid @RequestBody AssistantMessageRequest request) {
        AssistantMessageRequest.Page page = request.page();
        AssistantMessageRequest.Viewer viewer = request.viewer();
        AssistantAskCommand command = new AssistantAskCommand(request.conversationId(), request.message(),
                request.locale(),
                page == null ? null : new PageContext(page.path(), page.title()),
                viewer == null ? null : new ViewerContext(viewer.tripCode(),
                        viewer.loadPlanId() == null ? null : String.valueOf(viewer.loadPlanId()),
                        viewer.selectedPackageCode()),
                callerResolver.current(), null, null, AssistantChannel.APP);
        return ApiResponse.success(assistantService.ask(command));
    }

    // GET /api/assistant/conversations/{id}
    @GetMapping("/conversations/{id}")
    public ApiResponse<AssistantConversationResponse> conversation(@PathVariable String id) {
        return ApiResponse.success(assistantService.conversation(id, callerResolver.current()));
    }

    // GET /api/assistant/briefing
    @GetMapping("/briefing")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ApiResponse<AssistantBriefingResponse> briefing(@RequestParam(required = false) String locale) {
        return ApiResponse.success(briefingService.briefing(callerResolver.current(), locale));
    }
}
