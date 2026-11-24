package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.constant.assistant.AssistantRoute;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.assistant.AssistantPublicMessageRequest;
import fu.se184491.loadmaster_be.dto.request.assistant.AssistantRouteEventRequest;
import fu.se184491.loadmaster_be.dto.request.assistant.TryExtractRequest;
import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse;
import fu.se184491.loadmaster_be.service.assistant.AssistantService;
import fu.se184491.loadmaster_be.service.assistant.extract.TryExtractService;
import fu.se184491.loadmaster_be.service.assistant.gate.AssistantRateLimiter;
import fu.se184491.loadmaster_be.service.assistant.gate.ClientIpResolver;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantAskCommand;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantReply;
import fu.se184491.loadmaster_be.service.assistant.stats.RouteStatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Trợ lý của trang công khai: không cần token, không đọc dữ liệu người dùng. Người đã đăng nhập gọi vào đây vẫn là khách.
 *
 * <p>POST /api/assistant/public/messages · POST /api/assistant/public/try-extract
 * · POST /api/assistant/public/route-events
 */
@RestController
@RequestMapping("/api/assistant/public")
@RequiredArgsConstructor
public class AssistantPublicController {

    private final AssistantService assistantService;
    private final TryExtractService tryExtractService;
    private final ClientIpResolver clientIpResolver;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantProperties properties;
    private final RouteStatsService routeStats;

    // POST /api/assistant/public/messages
    @PostMapping("/messages")
    public ApiResponse<AssistantReply> ask(@Valid @RequestBody AssistantPublicMessageRequest request,
                                           HttpServletRequest http) {
        AssistantAskCommand command = new AssistantAskCommand(request.conversationId(), request.message(),
                request.locale(), null, null, null, request.guestKey(), clientIpResolver.resolve(http),
                AssistantChannel.PUBLIC);
        return ApiResponse.success(assistantService.ask(command));
    }

    // POST /api/assistant/public/try-extract
    @PostMapping("/try-extract")
    public ApiResponse<TryExtractResponse> tryExtract(@Valid @RequestBody TryExtractRequest request,
                                                      HttpServletRequest http) {
        return ApiResponse.success(tryExtractService.extract(request.text(), request.locale(),
                clientIpResolver.resolve(http)).data());
    }

    // POST /api/assistant/public/route-events
    // Chỉ cộng một số đếm (ngày · đường · chủ đề · ngôn ngữ). IP chỉ dùng cho hạn mức riêng của endpoint này, không lưu.
    @PostMapping("/route-events")
    public ResponseEntity<Void> routeEvent(@Valid @RequestBody AssistantRouteEventRequest request,
                                           HttpServletRequest http) {
        rateLimiter.check("route-event-ip:" + Objects.requireNonNullElse(clientIpResolver.resolve(http), "unknown"),
                properties.getRouteStats().getEventsPerHour());
        routeStats.record(AssistantRoute.valueOf(request.route()), request.topicId(), request.locale());
        return ResponseEntity.noContent().build();
    }
}
