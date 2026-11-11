package fu.se184491.loadmaster_be.service.credit;

import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditPricingResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CreditService {
    CreditBalanceResponse getBalance(Long companyId);
    Page<CreditTransactionResponse> getTransactions(Long companyId, Pageable pageable);
    /** Giá một credit, số credit một lần tối ưu và giới hạn nạp của công ty. */
    CreditPricingResponse getPricing(Long companyId);
    PaymentUrlResponse topup(Long companyId, CreditTopupRequest request, String clientIp);
    void deductCredit(Long companyId, String reference);
    default void deductCredit(Long companyId, String reference, int amount) {
        deductCredit(companyId, reference);
    }
    void refundCredit(Long companyId, String reference);
    void grantMonthlyCredits(Long companyId, int amount);
    void addCredits(Long companyId, int amount, String reference);
    default void resetBalance(Long companyId) {
    }
}
