package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCallbackHandler {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final VnPayClient vnPayClient;
    private final SubscriptionService subscriptionService;
    private final CreditService creditService;
    private final MockPaymentPolicy mockPaymentPolicy;
    private final CurrentUserService currentUserService;

    @Transactional
    public Map<String, String> handleVnPayCallback(Map<String, String> params) {
        // Public callbacks always require a signature. Client flags and transaction prefixes never bypass it.
        if (!vnPayClient.verifyCallback(params)) {
            return Map.of("RspCode", "97", "Message", "Invalid Checksum");
        }
        return processVerifiedCallback(params);
    }

    @Transactional
    public Map<String, String> handleMockCheckout(String reference) {
        mockPaymentPolicy.requireEnabled();
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayTransactionId(reference)
                .orElseThrow(() -> new IllegalArgumentException("Payment transaction not found"));
        Long companyId = currentUserService.getCurrentCompanyId();
        if (transaction.getCompany() == null || companyId == null
                || !companyId.equals(transaction.getCompany().getId())) {
            throw new AccessDeniedException("Payment does not belong to the current company");
        }
        return processVerifiedCallback(Map.of("vnp_TxnRef", reference, "vnp_ResponseCode", "00"));
    }

    private Map<String, String> processVerifiedCallback(Map<String, String> params) {
        Map<String, String> response = new HashMap<>();
        String txnRef = params.get("vnp_TxnRef");
        // 2. Find Payment Transaction
        Optional<PaymentTransaction> txnOpt = paymentTransactionRepository.findByGatewayTransactionId(txnRef);
        if (txnOpt.isEmpty()) {
            log.warn("VNPay callback order not found for txnRef: {}", txnRef);
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        PaymentTransaction txn = txnOpt.get();

        // 3. Idempotency Check: Already success
        if (txn.getStatus() == PaymentStatus.SUCCESS) {
            log.info("VNPay callback already confirmed success for txnRef: {}", txnRef);
            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        }

        // 4. Verify Amount
        String vnpAmountStr = params.get("vnp_Amount");
        if (vnpAmountStr != null && txn.getAmountVnd() != null) {
            long vnpAmount = Long.parseLong(vnpAmountStr);
            long expectedAmount = txn.getAmountVnd().multiply(BigDecimal.valueOf(100)).longValue();
            if (vnpAmount != expectedAmount) {
                log.warn("VNPay amount mismatch for txnRef {}: expected {} but got {}", txnRef, expectedAmount, vnpAmount);
                response.put("RspCode", "04");
                response.put("Message", "Invalid Amount");
                return response;
            }
        }

        // 5. Process Payment Result
        String responseCode = params.get("vnp_ResponseCode");
        txn.setPayload(params.toString());

        if ("00".equals(responseCode)) {
            txn.setStatus(PaymentStatus.SUCCESS);
            txn.preUpdate();
            paymentTransactionRepository.save(txn);
            log.info("Payment SUCCESS for txnRef: {} (type: {}, targetId: {})",
                    txnRef, txn.getOrderType(), txn.getTargetId());

            if ("SUBSCRIPTION".equalsIgnoreCase(txn.getOrderType()) && txn.getTargetId() != null) {
                subscriptionService.activateSubscription(txn.getCompany().getId(), txn.getTargetId());
            } else if ("CREDIT_TOPUP".equalsIgnoreCase(txn.getOrderType()) && txn.getTargetId() != null) {
                creditService.addCredits(txn.getCompany().getId(), txn.getTargetId().intValue(), txn.getGatewayTransactionId());
            }
        } else {
            txn.setStatus(PaymentStatus.FAILED);
            txn.preUpdate();
            paymentTransactionRepository.save(txn);
            log.warn("Payment FAILED for txnRef: {} with vnp_ResponseCode: {}", txnRef, responseCode);
        }

        response.put("RspCode", "00");
        response.put("Message", "Confirm Success");
        return response;
    }
}
