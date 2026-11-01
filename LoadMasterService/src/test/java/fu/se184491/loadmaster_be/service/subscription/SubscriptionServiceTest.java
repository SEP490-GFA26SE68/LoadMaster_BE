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
    private SubscriptionPlan ultimatePlan;

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

        ultimatePlan = SubscriptionPlan.builder()
                .id(3L)
                .name("Ultimate Plan")
                .tier(SubscriptionTier.ULTIMATE)
                .price(new BigDecimal("7900000"))
                .monthlyCredits(30000)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA_AI)
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
    @DisplayName("subscribe - Different plan while active subscription exists throws exception requiring support ticket")
    void testSubscribeAlreadyActiveThrowsException() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(freePlan)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(15))
                .build();
        lenient().when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub));
        lenient().when(companySubscriptionRepository.findByCompanyId(1L))
                .thenReturn(Optional.of(activeSub));

        SubscribeRequest request = SubscribeRequest.builder().planId(2L).build();

        AppException ex = assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, request, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("Support Ticket") || ex.getMessage().contains("phiếu hỗ trợ"));
    }

    @Test
    @DisplayName("subscribe - Different plan when subscription is EXPIRED also throws exception requiring support ticket (Method 3B)")
    void testSubscribeDifferentPlanWhenExpiredThrowsException() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription expiredBasicSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(freePlan)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        lenient().when(companySubscriptionRepository.findByCompanyId(1L))
                .thenReturn(Optional.of(expiredBasicSub));

        lenient().when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(freePlan));
        lenient().when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        AppException ex = assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, request, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("Support Ticket") || ex.getMessage().contains("phiếu hỗ trợ"));
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("subscribe - Same plan when subscription is EXPIRED allows renewal payment (Method 3B)")
    void testSubscribeSamePlanWhenExpiredAllowsRenewal() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription expiredProSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        lenient().when(companySubscriptionRepository.findByCompanyId(1L))
                .thenReturn(Optional.of(expiredProSub));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_RENEW_EXP")
                .transactionId("VNP_RENEW_EXP")
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
        assertEquals("VNP_RENEW_EXP", res.getTransactionId());
    }

    @Test
    @DisplayName("subscribe - EXPIRED Pro allows renewing Basic (lower tier) or Pro, but blocks Ultimate (higher tier)")
    void testSubscribeExpiredProAllowsBasicAndBlocksUltimate() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription expiredProSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(expiredProSub));
        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(freePlan));
        when(subscriptionPlanRepository.findById(3L)).thenReturn(Optional.of(ultimatePlan));

        SubscribeRequest ultimateReq = SubscribeRequest.builder()
                .planId(3L)
                .gateway(PaymentGateway.VNPAY)
                .build();
        assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, ultimateReq, "127.0.0.1"));

        SubscribeRequest basicReq = SubscribeRequest.builder()
                .planId(1L)
                .gateway(PaymentGateway.VNPAY)
                .build();
        PaymentUrlResponse basicRes = subscriptionService.subscribe(1L, basicReq, "127.0.0.1");
        assertNotNull(basicRes);
        assertEquals("ACTIVE", basicRes.getStatus());
    }

    @Test
    @DisplayName("subscribe - EXPIRED Ultimate allows renewing any of the 3 plans (Basic, Pro, Ultimate)")
    void testSubscribeExpiredUltimateAllowsAllThreePlans() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription expiredUltimateSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(ultimatePlan)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(2))
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(expiredUltimateSub));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_ULT_TO_PRO")
                .transactionId("VNP_ULT_TO_PRO")
                .status("PENDING")
                .build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION"),
                eq(2L), eq(new BigDecimal("1000000")), anyString(), any()))
                .thenReturn(paymentResponse);

        SubscribeRequest proReq = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();
        PaymentUrlResponse proRes = subscriptionService.subscribe(1L, proReq, "127.0.0.1");
        assertNotNull(proRes);
        assertEquals("VNP_ULT_TO_PRO", proRes.getTransactionId());
    }

    @Test
    @DisplayName("subscribe - Same active plan allows early renewal / buying additional month")
    void testSubscribeSameActivePlanAllowsEarlyRenewal() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(15))
                .build();
        lenient().when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub));
        lenient().when(companySubscriptionRepository.findByCompanyId(1L))
                .thenReturn(Optional.of(activeSub));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_RENEW_123")
                .transactionId("VNP_RENEW_123")
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
        assertEquals("VNP_RENEW_123", res.getTransactionId());
    }

    @Test
    @DisplayName("activateSubscription - Renewing same active plan extends existing expiresAt and grants additional credits")
    void testActivateSubscriptionExtendsExpiresAtForSamePlan() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime originalStart = LocalDateTime.now().minusDays(10);
        LocalDateTime existingExpiry = LocalDateTime.now().plusDays(20);
        CompanySubscription existingSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(originalStart)
                .expiresAt(existingExpiry)
                .autoRenew(true)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(existingSub));

        subscriptionService.activateSubscription(1L, 2L);

        assertEquals(originalStart, existingSub.getStartedAt());
        assertEquals(existingExpiry.plusMonths(1), existingSub.getExpiresAt());
        verify(companySubscriptionRepository).save(existingSub);
        verify(creditService).grantMonthlyCredits(1L, 200);
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
    @DisplayName("getCurrentSubscription - Returns subscription details when status is EXPIRED")
    void testGetCurrentSubscriptionWhenExpiredReturnsDetails() {
        CompanySubscription expiredSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.EXPIRED)
                .startedAt(LocalDateTime.now().minusDays(35))
                .expiresAt(LocalDateTime.now().minusDays(5))
                .autoRenew(false)
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(expiredSub));
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder().balance(180).build());

        SubscriptionCurrentResponse res = subscriptionService.getCurrentSubscription(1L);

        assertNotNull(res);
        assertEquals(10L, res.getSubscriptionId());
        assertEquals(SubscriptionStatus.EXPIRED, res.getStatus());
        assertEquals("Pro Plan", res.getPlanName());
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
