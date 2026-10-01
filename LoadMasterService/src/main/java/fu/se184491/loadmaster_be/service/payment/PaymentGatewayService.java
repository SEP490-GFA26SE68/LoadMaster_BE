package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentGatewayService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final VnPayClient vnPayClient;

    @Transactional
    public PaymentUrlResponse createPayment(Company company,
                                            PaymentGateway gateway,
                                            String orderType,
                                            Long targetId,
                                            BigDecimal amountVnd,
                                            String orderInfo,
                                            String ipAddress) {
        String prefix = (gateway != null ? gateway.name() : "VNPAY") + "_";
        String gatewayTxnId = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        PaymentTransaction txn = PaymentTransaction.builder()
                .company(company)
                .gateway(gateway != null ? gateway : PaymentGateway.VNPAY)
                .gatewayTransactionId(gatewayTxnId)
                .amountVnd(amountVnd)
                .orderType(orderType)
                .targetId(targetId)
                .status(PaymentStatus.PENDING)
                .payload("{\"orderInfo\":\"" + (orderInfo != null ? orderInfo : "") + "\"}")
                .build();

        txn = paymentTransactionRepository.save(txn);
        log.info("Created pending PaymentTransaction id={} gatewayTxnId={} for company={}",
                txn.getId(), gatewayTxnId, company.getId());

        String paymentUrl;
        if (gateway == PaymentGateway.MOCK) {
            paymentUrl = "/api/payment/mock-checkout?txnRef=" + gatewayTxnId;
        } else {
            paymentUrl = vnPayClient.createPaymentUrl(txn, orderInfo, ipAddress);
        }

        return PaymentUrlResponse.builder()
                .id(txn.getId())
                .transactionId(gatewayTxnId)
                .paymentUrl(paymentUrl)
                .status(txn.getStatus().name())
                .build();
    }
}
