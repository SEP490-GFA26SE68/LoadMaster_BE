package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentCallbackHandlerTest {

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private VnPayClient vnPayClient;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private CreditService creditService;

    @InjectMocks
    private PaymentCallbackHandler paymentCallbackHandler;

    private PaymentTransaction subscriptionTxn;
    private PaymentTransaction creditTxn;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).companyName("Acme").build();

        subscriptionTxn = PaymentTransaction.builder()
                .id(101L)
                .company(company)
                .gateway(PaymentGateway.VNPAY)
                .gatewayTransactionId("VNP_SUB_001")
                .amountVnd(new BigDecimal("1000000"))
                .orderType("SUBSCRIPTION")
                .targetId(2L)
                .status(PaymentStatus.PENDING)
                .build();

        creditTxn = PaymentTransaction.builder()
                .id(102L)
                .company(company)
                .gateway(PaymentGateway.VNPAY)
                .gatewayTransactionId("VNP_CREDIT_002")
                .amountVnd(new BigDecimal("100000"))
                .orderType("CREDIT_TOPUP")
                .targetId(50L) // 50 credits
                .status(PaymentStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("handleVnPayCallback - Invalid signature returns RspCode 97")
    void testInvalidSignature() {
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(false);

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("97", res.get("RspCode"));
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("handleVnPayCallback - Order not found returns RspCode 01")
    void testOrderNotFound() {
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("UNKNOWN")).thenReturn(Optional.empty());

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "UNKNOWN");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("01", res.get("RspCode"));
    }

    @Test
    @DisplayName("handleVnPayCallback - Already SUCCESS returns RspCode 00 (Idempotent)")
    void testAlreadySuccessIsIdempotent() {
        subscriptionTxn.setStatus(PaymentStatus.SUCCESS);
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("VNP_SUB_001"))
                .thenReturn(Optional.of(subscriptionTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", res.get("RspCode"));
        verify(subscriptionService, never()).activateSubscription(any(), any());
    }

    @Test
    @DisplayName("handleVnPayCallback - Success response activates subscription")
    void testSuccessActivatesSubscription() {
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("VNP_SUB_001"))
                .thenReturn(Optional.of(subscriptionTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "100000000"); // 1,000,000 * 100

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", res.get("RspCode"));
        assertEquals(PaymentStatus.SUCCESS, subscriptionTxn.getStatus());
        verify(subscriptionService).activateSubscription(1L, 2L);
        verify(paymentTransactionRepository).save(subscriptionTxn);
    }

    @Test
    @DisplayName("handleVnPayCallback - Success response adds credits for CREDIT_TOPUP")
    void testSuccessAddsCredits() {
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("VNP_CREDIT_002"))
                .thenReturn(Optional.of(creditTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_CREDIT_002");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "10000000"); // 100,000 * 100

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", res.get("RspCode"));
        assertEquals(PaymentStatus.SUCCESS, creditTxn.getStatus());
        verify(creditService).addCredits(1L, 50, "VNP_CREDIT_002");
        verify(paymentTransactionRepository).save(creditTxn);
    }
}
