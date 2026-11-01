package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
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
import java.util.Optional;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
@Slf4j
public class CreditController {

    private final CreditService creditService;
    private final PaymentGatewayService paymentGatewayService;
    private final CompanyRepository companyRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CurrentUserService currentUserService;

    public static final BigDecimal PRICE_PER_CREDIT_BASIC_VND = new BigDecimal("400");
    public static final BigDecimal PRICE_PER_CREDIT_PRO_VND = new BigDecimal("326");
    public static final BigDecimal PRICE_PER_CREDIT_ULTIMATE_VND = new BigDecimal("263");
    public static final int MAX_CUMULATIVE_CREDITS_BASIC = 3000;
    public static final int MAX_CUMULATIVE_CREDITS_PRO = 10000;

    @GetMapping("/balance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER') or hasAnyAuthority('COMPANY_ADMIN', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER') or hasAnyAuthority('COMPANY_ADMIN', 'DISPATCHER', 'SYSTEM_ADMIN', 'SYSTEM_MANAGER', 'SYSTEM_SUPPORTER')")
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
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('COMPANY_ADMIN')")
    public ResponseEntity<ApiResponse<PaymentUrlResponse>> topup(
            @Valid @RequestBody CreditTopupRequest request,
            HttpServletRequest servletRequest) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        if (subOpt.isPresent() && subOpt.get().getStatus() != SubscriptionStatus.ACTIVE) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "Gói cước của công ty không ở trạng thái hoạt động. Vui lòng gia hạn gói trước khi mua thêm credit."
            );
        }

        SubscriptionTier tier = (subOpt.isPresent()
                && subOpt.get().getPlan() != null
                && subOpt.get().getPlan().getTier() != null)
                ? subOpt.get().getPlan().getTier()
                : SubscriptionTier.BASIC;

        CreditBalanceResponse balanceRes = creditService.getBalance(companyId);
        int currentBalance = balanceRes != null ? balanceRes.getBalance() : 0;
        int requestedCredits = request.getCredits();

        BigDecimal pricePerCredit;
        if (tier == SubscriptionTier.BASIC) {
            if (currentBalance + requestedCredits > MAX_CUMULATIVE_CREDITS_BASIC) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        "Ở gói Basic, tổng số dư credit cộng dồn không được vượt quá 3.000 credit (hiện có "
                                + currentBalance + " credit). Vui lòng gửi ticket nâng cấp lên gói Pro hoặc Ultimate."
                );
            }
            pricePerCredit = PRICE_PER_CREDIT_BASIC_VND;
        } else if (tier == SubscriptionTier.PRO) {
            if (currentBalance + requestedCredits > MAX_CUMULATIVE_CREDITS_PRO) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        "Ở gói Pro, tổng số dư credit cộng dồn không được vượt quá 10.000 credit (hiện có "
                                + currentBalance + " credit). Vui lòng gửi ticket nâng cấp lên gói Ultimate để mua tự do."
                );
            }
            pricePerCredit = PRICE_PER_CREDIT_PRO_VND;
        } else {
            pricePerCredit = PRICE_PER_CREDIT_ULTIMATE_VND;
        }

        BigDecimal totalAmount = pricePerCredit.multiply(BigDecimal.valueOf(requestedCredits));
        PaymentGateway gateway = request.getGateway() != null ? request.getGateway() : PaymentGateway.VNPAY;
        String clientIp = servletRequest.getRemoteAddr();

        PaymentUrlResponse response = paymentGatewayService.createPayment(
                company,
                gateway,
                "CREDIT_TOPUP",
                Long.valueOf(requestedCredits),
                totalAmount,
                "Nạp " + requestedCredits + " credits",
                clientIp
        );

        return ResponseEntity.ok(ApiResponse.<PaymentUrlResponse>builder()
                .success(true)
                .data(response)
                .build());
    }
}
