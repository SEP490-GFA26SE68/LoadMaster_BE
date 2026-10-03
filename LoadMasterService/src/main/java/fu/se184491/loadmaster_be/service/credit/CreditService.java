package fu.se184491.loadmaster_be.service.credit;

import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CreditService {
    CreditBalanceResponse getBalance(Long companyId);
    Page<CreditTransactionResponse> getTransactions(Long companyId, Pageable pageable);
    void deductCredit(Long companyId, String reference);
    void refundCredit(Long companyId, String reference);
    void grantMonthlyCredits(Long companyId, int amount);
    void addCredits(Long companyId, int amount, String reference);
}
