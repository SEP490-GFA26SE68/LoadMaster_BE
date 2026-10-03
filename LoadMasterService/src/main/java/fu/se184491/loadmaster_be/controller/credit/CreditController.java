package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
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

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
@Slf4j
public class CreditController {

    private final CreditService creditService;
    private final PaymentGatewayService paymentGatewayService;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;

    public static final BigDecimal PRICE_PER_CREDIT_VND = new BigDecimal("5000");

    @GetMapping("/balance")
    @PreAuthorize("hasAnyAuthority('COMPANY_ADMIN', 'DISPATCHER', 'SYSTEM_MANAGER')")
    public ResponseEntity<ApiResponse<CreditBalanceResponse>> getBalance() {
        Long companyId = currentUserService.getCurrentCompanyId();
        CreditBalanceResponse response = creditService.getBalance(companyId);
        return ResponseEntity.ok(ApiResponse.<CreditBalanceResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAnyAuthority('COMPANY_ADMIN', 'DISPATCHER', 'SYSTEM_MANAGER')")
    public ResponseEntity<ApiResponse<Page<CreditTransactionResponse>>> getTransactions(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Page<CreditTransactionResponse> response = creditService.getTransactions(companyId, pageable);
        return ResponseEntity.ok(ApiResponse.<Page<CreditTransactionResponse>>builder()
                .success(true)
                .data(response)
                .build());
    }

    @PostMapping("/topup")
    @PreAuthorize("hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<PaymentUrlResponse>> topup(
            @Valid @RequestBody CreditTopupRequest request,
            HttpServletRequest servletRequest) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        BigDecimal totalAmount = PRICE_PER_CREDIT_VND.multiply(BigDecimal.valueOf(request.getCredits()));
        PaymentGateway gateway = request.getGateway() != null ? request.getGateway() : PaymentGateway.VNPAY;
        String clientIp = servletRequest.getRemoteAddr();

        PaymentUrlResponse response = paymentGatewayService.createPayment(
                company,
                gateway,
                "CREDIT_TOPUP",
                request.getCredits().longValue(),
                totalAmount,
                "Nạp " + request.getCredits() + " credits",
                clientIp
        );

        return ResponseEntity.ok(ApiResponse.<PaymentUrlResponse>builder()
                .success(true)
                .data(response)
                .build());
    }
}
