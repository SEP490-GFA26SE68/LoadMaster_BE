package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionRenewalResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import fu.se184491.loadmaster_be.service.subscription.Impl.SubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gia hạn gói: chỉ khi gói đã hết hạn hoặc còn trong 3 ngày cuối, đúng gói đang dùng, dùng lại thanh toán còn chờ. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionRenewalTest {

    private static final long COMPANY = 1L;

    @Mock CompanyRepository companyRepository;
    @Mock SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock CompanySubscriptionRepository companySubscriptionRepository;
    @Mock CreditService creditService;
    @Mock PaymentGatewayService paymentGatewayService;
    @Mock PaymentTransactionRepository paymentTransactionRepository;

    @InjectMocks SubscriptionServiceImpl service;

    private Company company;
    private SubscriptionPlan pro;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(COMPANY).companyName("Acme").build();
        pro = SubscriptionPlan.builder().id(2L).name("Pro").tier(SubscriptionTier.PRO)
                .price(new BigDecimal("1000000")).monthlyCredits(500).active(true).build();
        when(companyRepository.findById(COMPANY)).thenReturn(Optional.of(company));
        when(paymentTransactionRepository.findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private void subscription(SubscriptionStatus status, LocalDateTime expiresAt) {
        CompanySubscription sub = CompanySubscription.builder().id(10L).company(company).plan(pro)
                .status(status).expiresAt(expiresAt).build();
        when(companySubscriptionRepository.findByCompanyId(COMPANY)).thenReturn(Optional.of(sub));
    }

    private static ErrorCode codeOf(Throwable error) {
        return ((AppException) error).getErrorCode();
    }

    @Test
    void anExpiredPlanRenewsThroughTheGatewayEvenWhenNoLongerOnSale() {
        pro.setActive(false);
        subscription(SubscriptionStatus.EXPIRED, LocalDateTime.now().minusDays(5));
        PaymentUrlResponse created = PaymentUrlResponse.builder().transactionId("MOCK_R1").status("PENDING").build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.MOCK), eq("SUBSCRIPTION_RENEWAL"),
                eq(2L), eq(new BigDecimal("1000000")), anyString(), any())).thenReturn(created);

        assertThat(service.renew(COMPANY, PaymentGateway.MOCK, "127.0.0.1").getTransactionId()).isEqualTo("MOCK_R1");
    }

    @Test
    void aPlanFarFromItsEndIsNotRenewable() {
        subscription(SubscriptionStatus.ACTIVE, LocalDateTime.now().plusDays(20));

        assertThatThrownBy(() -> service.renew(COMPANY, PaymentGateway.MOCK, "127.0.0.1"))
                .extracting(SubscriptionRenewalTest::codeOf)
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_RENEWABLE);
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());
        assertThat(service.getRenewal(COMPANY))
                .extracting(SubscriptionRenewalResponse::getRenewable, SubscriptionRenewalResponse::getReason)
                .containsExactly(false, "NOT_DUE");
    }

    @Test
    void aCancelledContractAndNoPlanAreNotRenewable() {
        subscription(SubscriptionStatus.CANCELLED, LocalDateTime.now().plusDays(1));
        assertThat(service.getRenewal(COMPANY).getReason()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.renew(COMPANY, PaymentGateway.MOCK, null))
                .extracting(SubscriptionRenewalTest::codeOf)
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_RENEWABLE);

        when(companySubscriptionRepository.findByCompanyId(COMPANY)).thenReturn(Optional.empty());
        assertThat(service.getRenewal(COMPANY))
                .extracting(SubscriptionRenewalResponse::getRenewable, SubscriptionRenewalResponse::getReason)
                .containsExactly(false, "NO_SUBSCRIPTION");
        assertThatThrownBy(() -> service.renew(COMPANY, PaymentGateway.MOCK, null))
                .extracting(SubscriptionRenewalTest::codeOf)
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);
    }

    @Test
    void inTheLastDaysAPendingRenewalIsReusedAndReported() {
        subscription(SubscriptionStatus.ACTIVE, LocalDateTime.now().plusDays(2));
        PaymentTransaction pending = PaymentTransaction.builder().id(7L).company(company).gateway(PaymentGateway.MOCK)
                .gatewayTransactionId("MOCK_OLD").amountVnd(new BigDecimal("1000000")).orderType("SUBSCRIPTION_RENEWAL")
                .targetId(2L).status(PaymentStatus.PENDING).build();
        when(paymentTransactionRepository.findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(
                eq(COMPANY), eq(PaymentStatus.PENDING), any())).thenReturn(Optional.of(pending));

        PaymentUrlResponse res = service.renew(COMPANY, PaymentGateway.MOCK, "127.0.0.1");

        assertThat(res.getTransactionId()).isEqualTo("MOCK_OLD");
        assertThat(res.getPaymentUrl()).isEqualTo("/api/payment/mock-checkout?txnRef=MOCK_OLD");
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());

        SubscriptionRenewalResponse view = service.getRenewal(COMPANY);
        assertThat(view.getRenewable()).isTrue();
        assertThat(view.getPlanName()).isEqualTo("Pro");
        assertThat(view.getAmountVnd()).isEqualByComparingTo("1000000");
        assertThat(view.getRenewalWindowDays()).isEqualTo(3);
        assertThat(view.getPendingPayment())
                .extracting(SubscriptionRenewalResponse.PendingPayment::getTransactionId,
                        SubscriptionRenewalResponse.PendingPayment::getOrderType,
                        SubscriptionRenewalResponse.PendingPayment::getPlanId)
                .containsExactly("MOCK_OLD", "SUBSCRIPTION_RENEWAL", 2L);
        verify(paymentTransactionRepository).findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(
                COMPANY, PaymentStatus.PENDING, List.of("SUBSCRIPTION", "SUBSCRIPTION_YEARLY", "SUBSCRIPTION_RENEWAL"));
    }
}
