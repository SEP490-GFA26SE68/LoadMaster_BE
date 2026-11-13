package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditPricingResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
@Slf4j
public class CreditController {

    private final CreditService creditService;
    private final CurrentUserService currentUserService;

    @GetMapping("/balance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER')")
    public ResponseEntity<ApiResponse<CreditBalanceResponse>> getBalance(
            @RequestParam(required = false) Long companyId) {
        Long targetCompanyId = resolveTargetCompanyId(companyId);
        CreditBalanceResponse response = creditService.getBalance(targetCompanyId);
        return ResponseEntity.ok(ApiResponse.<CreditBalanceResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER')")
    public ResponseEntity<ApiResponse<Page<CreditTransactionResponse>>> getTransactions(
            @RequestParam(required = false) Long companyId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Long targetCompanyId = resolveTargetCompanyId(companyId);
        Page<CreditTransactionResponse> response = creditService.getTransactions(targetCompanyId, pageable);
        return ResponseEntity.ok(ApiResponse.<Page<CreditTransactionResponse> >builder()
                .success(true)
                .data(response)
                .build());
    }

    /** Giá credit theo hạng gói của công ty đang gọi, credit một lần tối ưu và giới hạn nạp. */
    @GetMapping("/pricing")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER')")
    public ResponseEntity<ApiResponse<CreditPricingResponse>> getPricing() {
        CreditPricingResponse response = creditService.getPricing(currentUserService.getCurrentCompanyId());
        return ResponseEntity.ok(ApiResponse.<CreditPricingResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    private Long resolveTargetCompanyId(Long requestedCompanyId) {
        boolean isPlatformUser = currentUserService.hasRole("SYSTEM_SUPPORTER")
                || currentUserService.hasRole("SYSTEM_MANAGER")
                || currentUserService.hasRole("SYSTEM_ADMIN");
        if (isPlatformUser && requestedCompanyId != null) {
            return requestedCompanyId;
        }
        return currentUserService.getCurrentCompanyId();
    }

    @PostMapping("/topup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PaymentUrlResponse>> topup(
            @Valid @RequestBody CreditTopupRequest request,
            HttpServletRequest servletRequest) {
        Long companyId = currentUserService.getCurrentCompanyId();
        PaymentUrlResponse response = creditService.topup(companyId, request, servletRequest.getRemoteAddr());

        return ResponseEntity.ok(ApiResponse.<PaymentUrlResponse>builder()
                .success(true)
                .data(response)
                .build());
    }
}
