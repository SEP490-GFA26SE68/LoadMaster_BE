package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Ai được xác nhận một thanh toán giả lập: giao dịch của công ty (gói, gia hạn, nạp credit) chỉ người đăng nhập của
 * chính công ty đó. Giao dịch đăng ký trả phí ({@code REG_…}) là của khách chưa có tài khoản nên không đòi đăng nhập;
 * mã của nó do máy chủ sinh ngẫu nhiên và chỉ trả cho phiên đăng ký đã tạo nó.
 */
@Service
@RequiredArgsConstructor
public class MockCheckoutAccess {

    static final String REGISTRATION_PREFIX = "REG_";

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public void requireAllowed(String txnRef) {
        if (txnRef != null && txnRef.startsWith(REGISTRATION_PREFIX)) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Long companyId = currentUserService.getCurrentCompanyId();
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayTransactionId(txnRef)
                .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND));
        // Giao dịch của công ty khác trả như không tồn tại, không lộ mã giao dịch có thật
        if (transaction.getCompany() == null || !Objects.equals(transaction.getCompany().getId(), companyId)) {
            throw new AppException(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND);
        }
    }
}
