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

    @Mock
    private fu.se184491.loadmaster_be.service.registration.GuestRegistrationService guestRegistrationService;

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
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("UNKNOWN")).thenReturn(Optional.empty());

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
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_SUB_001"))
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
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_SUB_001"))
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
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_CREDIT_002"))
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

    @Test
    @DisplayName("handleVnPayCallback - vnpay.mock on: skips signature verification")
    void testMockConfigOnSkipsSignatureVerification() {
        when(vnPayClient.isMock()).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_SUB_001"))
                .thenReturn(Optional.of(subscriptionTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");
        params.put("vnp_ResponseCode", "00");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", res.get("RspCode"));
        assertEquals(PaymentStatus.SUCCESS, subscriptionTxn.getStatus());
        verify(vnPayClient, never()).verifyCallback(anyMap());
        verify(subscriptionService).activateSubscription(1L, 2L);
    }

    @Test
    @DisplayName("handleVnPayCallback - vnpay.mock off: vnp_Mock=true with a bad signature is rejected")
    void testMockParamIgnoredWhenMockConfigOff() {
        when(vnPayClient.isMock()).thenReturn(false);
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(false);

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "100000000");
        params.put("vnp_Mock", "true");
        params.put("vnp_SecureHash", "forged");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("97", res.get("RspCode"));
        assertEquals(PaymentStatus.PENDING, subscriptionTxn.getStatus());
        verifyNoInteractions(paymentTransactionRepository, subscriptionService, creditService);
    }

    @Test
    @DisplayName("handleVnPayCallback - vnpay.mock off: MOCK_ transaction prefix does not skip signature verification")
    void testMockPrefixIgnoredWhenMockConfigOff() {
        when(vnPayClient.isMock()).thenReturn(false);
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(false);

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "MOCK_0123456789ABCDEF");
        params.put("vnp_ResponseCode", "00");

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("97", res.get("RspCode"));
        verifyNoInteractions(paymentTransactionRepository, subscriptionService, creditService);
    }

    @Test
    @DisplayName("PaymentTransaction - createdAt & updatedAt format yyyy-MM-dd HH:mm:ss, NOT NULL, updatedAt >= createdAt, and DB CHECK annotations")
    void testPaymentTransactionTimestampFormatAndConstraints() throws Exception {
        PaymentTransaction txn = PaymentTransaction.builder()
                .gateway(PaymentGateway.VNPAY)
                .gatewayTransactionId("VNP_TEST_TIME_01")
                .amountVnd(new BigDecimal("500000"))
                .status(PaymentStatus.PENDING)
                .build();

        txn.prePersist();

        assertNotNull(txn.getCreatedAt(), "createdAt must not be null on creation");
        assertNotNull(txn.getUpdatedAt(), "updatedAt must not be null on creation");
        assertEquals(0, txn.getCreatedAt().getNano(), "createdAt must be truncated to seconds");
        assertEquals(0, txn.getUpdatedAt().getNano(), "updatedAt must be truncated to seconds");
        assertEquals(txn.getCreatedAt(), txn.getUpdatedAt(), "updatedAt must equal createdAt on creation");

        var converter = new fu.se184491.loadmaster_be.entity.support.SupportTicket.LocalDateTimeSecondsConverter();
        String createdStr = converter.convertToDatabaseColumn(txn.getCreatedAt());
        String updatedStr = converter.convertToDatabaseColumn(txn.getUpdatedAt());
        assertTrue(createdStr.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$"));
        assertTrue(updatedStr.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$"));

        txn.preUpdate();
        assertEquals(0, txn.getUpdatedAt().getNano(), "updatedAt must be truncated to seconds after update");
        assertFalse(txn.getUpdatedAt().isBefore(txn.getCreatedAt()), "updatedAt must be >= createdAt");

        var createdField = PaymentTransaction.class.getDeclaredField("createdAt");
        var updatedField = PaymentTransaction.class.getDeclaredField("updatedAt");
        var createdCol = createdField.getAnnotation(jakarta.persistence.Column.class);
        var updatedCol = updatedField.getAnnotation(jakarta.persistence.Column.class);
        assertEquals("varchar(19)", createdCol.columnDefinition());
        assertFalse(createdCol.nullable());
        assertFalse(createdCol.updatable());
        assertEquals("varchar(19)", updatedCol.columnDefinition());
        assertFalse(updatedCol.nullable());

        assertNotNull(createdField.getAnnotation(org.hibernate.annotations.Check.class));
        assertNotNull(updatedField.getAnnotation(org.hibernate.annotations.Check.class));
        assertNotNull(PaymentTransaction.class.getAnnotation(org.hibernate.annotations.Check.class));
    }

    @Test
    @DisplayName("handleVnPayCallback - Success response for SUBSCRIPTION_YEARLY activates yearly subscription")
    void testSuccessActivatesYearlySubscription() {
        subscriptionTxn.setOrderType("SUBSCRIPTION_YEARLY");
        subscriptionTxn.setAmountVnd(new BigDecimal("12000000"));
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_SUB_001"))
                .thenReturn(Optional.of(subscriptionTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "1200000000"); // 12,000,000 * 100

        Map<String, String> res = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", res.get("RspCode"));
        assertEquals(PaymentStatus.SUCCESS, subscriptionTxn.getStatus());
        verify(subscriptionService).activateSubscription(1L, 2L, fu.se184491.loadmaster_be.constant.billing.BillingCycle.YEARLY);
        verify(paymentTransactionRepository).save(subscriptionTxn);
    }

    @Test
    @DisplayName("Delegate to GuestRegistrationService when txnRef starts with REG_")
    void testDelegateToGuestRegistrationService() {
        Map<String, String> params = Map.of("vnp_TxnRef", "REG_ABC123");
        when(vnPayClient.verifyCallback(params)).thenReturn(true);
        when(guestRegistrationService.handlePaymentCallback(params)).thenReturn(Map.of("RspCode", "00", "Message", "Confirm Success"));

        Map<String, String> result = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("00", result.get("RspCode"));
        verify(guestRegistrationService).handlePaymentCallback(params);
        verifyNoInteractions(paymentTransactionRepository);
    }

    @Test
    @DisplayName("REG_ callback with an invalid signature is rejected before reaching guest registration")
    void testRegistrationCallbackWithInvalidSignatureIsRejected() {
        Map<String, String> params = Map.of("vnp_TxnRef", "REG_12345", "vnp_ResponseCode", "00",
                "vnp_Mock", "true", "vnp_SecureHash", "forged");
        when(vnPayClient.isMock()).thenReturn(false);
        when(vnPayClient.verifyCallback(params)).thenReturn(false);

        Map<String, String> result = paymentCallbackHandler.handleVnPayCallback(params);

        assertEquals("97", result.get("RspCode"));
        assertEquals("Invalid Checksum", result.get("Message"));
        verifyNoInteractions(guestRegistrationService);
    }

    @Test
    @DisplayName("REG_ callback skips the signature only when the server runs vnpay.mock")
    void testRegistrationCallbackInMockModeReachesGuestRegistration() {
        Map<String, String> params = Map.of("vnp_TxnRef", "REG_12345", "vnp_ResponseCode", "00");
        when(vnPayClient.isMock()).thenReturn(true);
        when(guestRegistrationService.handlePaymentCallback(params)).thenReturn(Map.of("RspCode", "00"));

        assertEquals("00", paymentCallbackHandler.handleVnPayCallback(params).get("RspCode"));
        verify(vnPayClient, never()).verifyCallback(anyMap());
    }

    @Test
    @DisplayName("handleVnPayCallback - Paid renewal extends the same plan")
    void testSuccessfulRenewalActivatesTheSamePlan() {
        subscriptionTxn.setOrderType("SUBSCRIPTION_RENEWAL");
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_SUB_001"))
                .thenReturn(Optional.of(subscriptionTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_SUB_001");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "100000000");

        assertEquals("00", paymentCallbackHandler.handleVnPayCallback(params).get("RspCode"));
        verify(subscriptionService).activateSubscription(1L, 2L);
    }

    @Test
    @DisplayName("handleVnPayCallback - IPN rồi trang trả về cùng giao dịch: chỉ cộng credit một lần, đọc dòng có khoá")
    void testReturnAfterIpnGrantsOnce() {
        when(vnPayClient.verifyCallback(anyMap())).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionIdForUpdate("VNP_CREDIT_002"))
                .thenReturn(Optional.of(creditTxn));

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "VNP_CREDIT_002");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "10000000");

        assertEquals("00", paymentCallbackHandler.handleVnPayCallback(params).get("RspCode"));
        assertEquals("00", paymentCallbackHandler.handleVnPayCallback(params).get("RspCode"));

        verify(creditService, times(1)).addCredits(1L, 50, "VNP_CREDIT_002");
        verify(paymentTransactionRepository, times(1)).save(creditTxn);
        verify(paymentTransactionRepository, never()).findByGatewayTransactionId(any());
    }
}
