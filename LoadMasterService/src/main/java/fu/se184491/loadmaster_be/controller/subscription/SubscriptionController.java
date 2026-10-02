package fu.se184491.loadmaster_be.controller.subscription;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final CurrentUserService currentUserService;

    @GetMapping("/current")
    @PreAuthorize("hasAnyAuthority('COMPANY_ADMIN', 'DISPATCHER', 'SYSTEM_MANAGER')")
    public ResponseEntity<ApiResponse<SubscriptionCurrentResponse>> getCurrentSubscription() {
        Long companyId = currentUserService.getCurrentCompanyId();
        SubscriptionCurrentResponse response = subscriptionService.getCurrentSubscription(companyId);
        return ResponseEntity.ok(ApiResponse.<SubscriptionCurrentResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    @PostMapping("/subscribe")
    @PreAuthorize("hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<PaymentUrlResponse>> subscribe(
            @Valid @RequestBody SubscribeRequest request,
            HttpServletRequest servletRequest) {
        Long companyId = currentUserService.getCurrentCompanyId();
        String clientIp = servletRequest.getRemoteAddr();
        PaymentUrlResponse response = subscriptionService.subscribe(companyId, request, clientIp);
        return ResponseEntity.ok(ApiResponse.<PaymentUrlResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> cancelSubscription() {
        Long companyId = currentUserService.getCurrentCompanyId();
        subscriptionService.cancelSubscription(companyId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Subscription cancelled successfully")
                .build());
    }
}
