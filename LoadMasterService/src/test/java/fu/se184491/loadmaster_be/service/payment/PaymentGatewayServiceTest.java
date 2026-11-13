package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private VnPayClient vnPayClient;

    @InjectMocks
    private PaymentGatewayService paymentGatewayService;

    private final Company company = Company.builder().id(1L).companyName("Acme").build();

    @Test
    @DisplayName("createPayment - MOCK gateway is refused when vnpay.mock is off, nothing is saved")
    void createPayment_mockGatewayRefusedWhenMockConfigOff() {
        when(vnPayClient.isMock()).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> paymentGatewayService.createPayment(
                company, PaymentGateway.MOCK, "CREDIT_TOPUP", 50L, new BigDecimal("250000"), "Nạp 50 credits", "127.0.0.1"));

        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        verifyNoInteractions(paymentTransactionRepository);
    }

    @Test
    @DisplayName("createPayment - MOCK gateway returns the mock-checkout link when vnpay.mock is on")
    void createPayment_mockGatewayAllowedWhenMockConfigOn() {
        when(vnPayClient.isMock()).thenReturn(true);
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction txn = inv.getArgument(0);
            txn.setId(7L);
            return txn;
        });

        PaymentUrlResponse response = paymentGatewayService.createPayment(
                company, PaymentGateway.MOCK, "CREDIT_TOPUP", 50L, new BigDecimal("250000"), "Nạp 50 credits", "127.0.0.1");

        assertTrue(response.getTransactionId().startsWith("MOCK_"));
        assertEquals("/api/payment/mock-checkout?txnRef=" + response.getTransactionId(), response.getPaymentUrl());
        assertEquals("PENDING", response.getStatus());
    }
}
