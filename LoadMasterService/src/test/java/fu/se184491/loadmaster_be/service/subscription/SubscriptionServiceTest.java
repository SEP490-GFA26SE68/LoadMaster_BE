package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import fu.se184491.loadmaster_be.service.subscription.Impl.SubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SubscriptionServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private CreditService creditService;

    @Mock
    private PaymentGatewayService paymentGatewayService;

    @InjectMocks
    private SubscriptionServiceImpl subscriptionService;

    private Company company;
    private SubscriptionPlan proPlan;
    private SubscriptionPlan freePlan;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).companyName("Acme Logistics").build();

        proPlan = SubscriptionPlan.builder()
                .id(2L)
                .name("Pro Plan")
                .tier(SubscriptionTier.PRO)
                .price(new BigDecimal("1000000"))
                .monthlyCredits(200)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .active(true)
                .build();

        freePlan = SubscriptionPlan.builder()
                .id(1L)
                .name("Basic Free")
                .tier(SubscriptionTier.BASIC)
                .price(BigDecimal.ZERO)
                .monthlyCredits(50)
                .algorithmTier(AlgorithmTier.EP_DBLF)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("subscribe - Paid plan triggers payment gateway")
    void testSubscribePaidPlanTriggersPaymentGateway() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("https://sandbox.vnpayment.vn/vpcpay?...")
                .transactionId("VNP_123")
                .status("PENDING")
                .build();

        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION"),
                eq(2L), eq(new BigDecimal("1000000")), anyString(), any()))
                .thenReturn(paymentResponse);

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        PaymentUrlResponse res = subscriptionService.subscribe(1L, request, "127.0.0.1");

        assertNotNull(res);
        assertEquals("VNP_123", res.getTransactionId());
        verify(paymentGatewayService).createPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("subscribe - Free plan activates immediately")
    void testSubscribeFreePlanActivatesImmediately() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(freePlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(1L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        PaymentUrlResponse res = subscriptionService.subscribe(1L, request, "127.0.0.1");

        assertNotNull(res);
        assertEquals("ACTIVE", res.getStatus());
        verify(creditService).grantMonthlyCredits(1L, 50);
        verify(companySubscriptionRepository).save(any(CompanySubscription.class));
    }

    @Test
    @DisplayName("subscribe - Active subscription already exists throws exception")
    void testSubscribeAlreadyActiveThrowsException() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(15))
                .build();
        when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub));

        SubscribeRequest request = SubscribeRequest.builder().planId(2L).build();

        assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, request, "127.0.0.1"));
    }

    @Test
    @DisplayName("activateSubscription - Saves active subscription and grants monthly credits")
    void testActivateSubscriptionSuccess() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());

        subscriptionService.activateSubscription(1L, 2L);

        verify(companySubscriptionRepository).save(any(CompanySubscription.class));
        verify(creditService).grantMonthlyCredits(1L, 200);
    }

    @Test
    @DisplayName("cancelSubscription - Disables autoRenew")
    void testCancelSubscriptionSuccess() {
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .status(SubscriptionStatus.ACTIVE)
                .autoRenew(true)
                .build();
        when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub));

        subscriptionService.cancelSubscription(1L);

        assertFalse(activeSub.getAutoRenew());
        verify(companySubscriptionRepository).save(activeSub);
    }

    @Test
    @DisplayName("getCurrentSubscription - Returns current active subscription with credit balance")
    void testGetCurrentSubscriptionSuccess() {
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(5))
                .expiresAt(LocalDateTime.now().plusDays(25))
                .autoRenew(true)
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeSub));
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder().balance(180).build());

        SubscriptionCurrentResponse res = subscriptionService.getCurrentSubscription(1L);

        assertNotNull(res);
        assertEquals("Pro Plan", res.getPlanName());
        assertEquals(SubscriptionTier.PRO, res.getTier());
        assertEquals(180, res.getCreditsBalance());
        assertEquals(200, res.getCreditsMonthlyGrant());
    }

    @Test
    @DisplayName("activateSubscription - Sets expiresAt to plusYears(1) when plan BillingCycle is YEARLY")
    void testActivateSubscription_WithYearlyBillingCycle_SetsExpiresAtPlusOneYear() {
        SubscriptionPlan yearlyPlan = SubscriptionPlan.builder()
                .id(3L)
                .name("Enterprise Yearly")
                .tier(SubscriptionTier.PRO)
                .billingCycle(BillingCycle.YEARLY)
                .price(new BigDecimal("10000000"))
                .monthlyCredits(500)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .active(true)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(3L)).thenReturn(Optional.of(yearlyPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());

        ArgumentCaptor<CompanySubscription> captor =
                ArgumentCaptor.forClass(CompanySubscription.class);

        subscriptionService.activateSubscription(1L, 3L);

        verify(companySubscriptionRepository).save(captor.capture());
        CompanySubscription saved = captor.getValue();
        assertNotNull(saved.getStartedAt());
        assertEquals(saved.getStartedAt().plusYears(1), saved.getExpiresAt());
    }

    @Test
    @DisplayName("activateSubscription - Sets expiresAt to plusMonths(1) when plan BillingCycle is MONTHLY")
    void testActivateSubscription_WithMonthlyBillingCycle_SetsExpiresAtPlusOneMonth() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());

        ArgumentCaptor<CompanySubscription> captor =
                ArgumentCaptor.forClass(CompanySubscription.class);

        subscriptionService.activateSubscription(1L, 2L);

        verify(companySubscriptionRepository).save(captor.capture());
        CompanySubscription saved = captor.getValue();
        assertNotNull(saved.getStartedAt());
        assertEquals(saved.getStartedAt().plusMonths(1), saved.getExpiresAt());
    }

    @Test
    @DisplayName("Invoice entity - Has company, companySubscription, billingPeriod, createdAt and no legacy subscription field")
    void testInvoiceEntityFieldsUpdated() {
        Class<?> invoiceClass = Invoice.class;
        assertDoesNotThrow(() -> invoiceClass.getDeclaredField("company"),
                "Invoice must have a 'company' field");
        assertDoesNotThrow(() -> invoiceClass.getDeclaredField("companySubscription"),
                "Invoice must have a 'companySubscription' field");
        assertDoesNotThrow(() -> invoiceClass.getDeclaredField("billingPeriod"),
                "Invoice must have a 'billingPeriod' field");
        assertDoesNotThrow(() -> invoiceClass.getDeclaredField("createdAt"),
                "Invoice must have a 'createdAt' field");
        assertThrows(NoSuchFieldException.class, () -> invoiceClass.getDeclaredField("subscription"),
                "Invoice must not reference legacy 'subscription' field");
    }

    @Test
    @DisplayName("Orphan Subscription entity - Removed from billing entity package")
    void testOrphanSubscriptionEntityRemoved() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("fu.se184491.loadmaster_be.entity.billing.Subscription"),
                "Orphan entity fu.se184491.loadmaster_be.entity.billing.Subscription should be deleted");
    }
}
