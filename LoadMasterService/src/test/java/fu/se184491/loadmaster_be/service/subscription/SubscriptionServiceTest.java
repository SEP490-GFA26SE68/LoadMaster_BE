package fu.se184491.loadmaster_be.service.subscription;

import com.fasterxml.jackson.annotation.JsonFormat;
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
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import fu.se184491.loadmaster_be.service.subscription.Impl.SubscriptionServiceImpl;
import jakarta.persistence.Convert;
import org.hibernate.annotations.Check;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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
    @DisplayName("CompanySubscription & SubscriptionCurrentResponse - startedAt and expiresAt use yyyy-MM-dd HH:mm:ss format, DB Check constraints, and truncate to seconds")
    void testCompanySubscriptionTimestampsFormattedAsSeconds() throws Exception {
        Field startedAtField = CompanySubscription.class.getDeclaredField("startedAt");
        Field expiresAtField = CompanySubscription.class.getDeclaredField("expiresAt");

        Convert startedConvert = startedAtField.getAnnotation(Convert.class);
        Convert expiresConvert = expiresAtField.getAnnotation(Convert.class);
        assertNotNull(startedConvert, "CompanySubscription.startedAt must have @Convert");
        assertNotNull(expiresConvert, "CompanySubscription.expiresAt must have @Convert");
        assertEquals(SupportTicket.LocalDateTimeSecondsConverter.class, startedConvert.converter());
        assertEquals(SupportTicket.LocalDateTimeSecondsConverter.class, expiresConvert.converter());

        assertNotNull(startedAtField.getAnnotation(Check.class), "startedAt must have @Check constraint");
        assertNotNull(expiresAtField.getAnnotation(Check.class), "expiresAt must have @Check constraint");
        assertNotNull(CompanySubscription.class.getAnnotation(Check.class), "CompanySubscription class must have expires_at >= started_at @Check");

        Field dtoStartedAt = SubscriptionCurrentResponse.class.getDeclaredField("startedAt");
        Field dtoExpiresAt = SubscriptionCurrentResponse.class.getDeclaredField("expiresAt");
        JsonFormat startedJson = dtoStartedAt.getAnnotation(JsonFormat.class);
        JsonFormat expiresJson = dtoExpiresAt.getAnnotation(JsonFormat.class);
        assertNotNull(startedJson, "SubscriptionCurrentResponse.startedAt must have @JsonFormat");
        assertNotNull(expiresJson, "SubscriptionCurrentResponse.expiresAt must have @JsonFormat");
        assertEquals("yyyy-MM-dd HH:mm:ss", startedJson.pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss", expiresJson.pattern());

        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());

        ArgumentCaptor<CompanySubscription> captor = ArgumentCaptor.forClass(CompanySubscription.class);
        subscriptionService.activateSubscription(1L, 2L);

        verify(companySubscriptionRepository).save(captor.capture());
        CompanySubscription saved = captor.getValue();
        assertEquals(0, saved.getStartedAt().getNano(), "startedAt must be truncated to seconds");
        assertEquals(0, saved.getExpiresAt().getNano(), "expiresAt must be truncated to seconds");
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
    @DisplayName("subscribe - Higher tier plan while active subscription exists triggers payment gateway (self-upgrade)")
    void testSubscribeHigherTierWhileActiveTriggersPaymentGateway() {
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
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_UPGRADE_PRO")
                .transactionId("VNP_UPGRADE_PRO")
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
        assertEquals("VNP_UPGRADE_PRO", res.getTransactionId());
        verify(paymentGatewayService).createPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("subscribe - Lower tier plan while active subscription exists throws exception")
    void testSubscribeLowerTierWhileActiveThrowsException() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        CompanySubscription activeProSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusDays(15))
                .build();
        lenient().when(companySubscriptionRepository.findByCompanyId(1L))
                .thenReturn(Optional.of(activeProSub));
        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(freePlan));

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(1L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, request, "127.0.0.1"));
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("subscribe - Different higher plan when subscription is EXPIRED allows direct subscription without ticket")
    void testSubscribeDifferentPlanWhenExpiredAllowsSubscription() {
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
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_EXP_TO_PRO")
                .transactionId("VNP_EXP_TO_PRO")
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
        assertEquals("VNP_EXP_TO_PRO", res.getTransactionId());
    }

    @Test
    @DisplayName("subscribe - Same plan when subscription is EXPIRED allows renewal payment")
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
    @DisplayName("subscribe - EXPIRED Pro allows subscribing to both Basic (lower tier) and Ultimate (higher tier)")
    void testSubscribeExpiredProAllowsBasicAndUltimate() {
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

        PaymentUrlResponse ultimatePayment = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_PRO_TO_ULT")
                .transactionId("VNP_PRO_TO_ULT")
                .status("PENDING")
                .build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION"),
                eq(3L), eq(new BigDecimal("7900000")), anyString(), any()))
                .thenReturn(ultimatePayment);

        SubscribeRequest ultimateReq = SubscribeRequest.builder()
                .planId(3L)
                .gateway(PaymentGateway.VNPAY)
                .build();
        PaymentUrlResponse ultRes = subscriptionService.subscribe(1L, ultimateReq, "127.0.0.1");
        assertNotNull(ultRes);
        assertEquals("VNP_PRO_TO_ULT", ultRes.getTransactionId());

        SubscribeRequest basicReq = SubscribeRequest.builder()
                .planId(1L)
                .gateway(PaymentGateway.VNPAY)
                .build();
        PaymentUrlResponse basicRes = subscriptionService.subscribe(1L, basicReq, "127.0.0.1");
        assertNotNull(basicRes);
        assertEquals("ACTIVE", basicRes.getStatus());
    }

    @Test
    @DisplayName("activateSubscription - Upgrading active plan to a higher tier resets existing credit balance to 0 before granting new monthly credits")
    void testActivateSubscriptionUpgradingActivePlanResetsBalanceAndGrantsNewCredits() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime originalStart = LocalDateTime.now().minusDays(10).withNano(0);
        LocalDateTime existingExpiry = LocalDateTime.now().plusDays(20).withNano(0);
        CompanySubscription activeBasicSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(freePlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(originalStart)
                .expiresAt(existingExpiry)
                .autoRenew(true)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeBasicSub));

        subscriptionService.activateSubscription(1L, 2L);

        verify(creditService).resetBalance(1L);
        verify(creditService).grantMonthlyCredits(1L, 200);
        assertEquals(proPlan, activeBasicSub.getPlan());
        assertEquals(SubscriptionStatus.ACTIVE, activeBasicSub.getStatus());
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
    @DisplayName("activateSubscription - Renewing same active plan extends existing expiresAt without granting immediate credits")
    void testActivateSubscriptionExtendsExpiresAtForSamePlan() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime originalStart = LocalDateTime.now().minusDays(10).withNano(0);
        LocalDateTime existingExpiry = LocalDateTime.now().plusDays(20).withNano(0);
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
        verify(creditService, never()).grantMonthlyCredits(anyLong(), anyInt());
        verify(creditService, never()).resetBalance(anyLong());
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
    @DisplayName("subscribe - Yearly billing cycle multiplies monthly plan price by 12 and uses SUBSCRIPTION_YEARLY orderType")
    void testSubscribeWithYearlyBillingCycleMultipliesPriceBy12AndUsesYearlyOrderType() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());
        when(companySubscriptionRepository.findByCompanyIdAndStatus(1L, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_YEARLY_01")
                .transactionId("VNP_YEARLY_01")
                .status("PENDING")
                .build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION_YEARLY"),
                eq(2L), eq(new BigDecimal("12000000")), anyString(), any()))
                .thenReturn(paymentResponse);

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .billingCycle(BillingCycle.YEARLY)
                .build();

        PaymentUrlResponse res = subscriptionService.subscribe(1L, request, "127.0.0.1");

        assertNotNull(res);
        assertEquals("VNP_YEARLY_01", res.getTransactionId());
        verify(paymentGatewayService).createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION_YEARLY"),
                eq(2L), eq(new BigDecimal("12000000")), anyString(), any());
    }

    @Test
    @DisplayName("activateSubscription - Requested YEARLY billing cycle on a MONTHLY base plan extends expiresAt to 12 months from startedAt without immediate credit grant")
    void testActivateSubscriptionWithYearlyRequestedCycleExtendsByOneYear() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime originalStart = LocalDateTime.now().minusMonths(1).withNano(0);
        LocalDateTime existingExpiry = originalStart.plusMonths(4); // already has 4 months
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

        subscriptionService.activateSubscription(1L, 2L, BillingCycle.YEARLY);

        assertEquals(originalStart, existingSub.getStartedAt());
        assertEquals(originalStart.plusMonths(12), existingSub.getExpiresAt());
        verify(companySubscriptionRepository).save(existingSub);
        verify(creditService, never()).grantMonthlyCredits(anyLong(), anyInt());
    }

    @Test
    @DisplayName("subscribe - Renewing active 4-month plan by YEARLY charges only for the remaining 8 months to reach 12 months")
    void testSubscribeYearlyWhileActiveWith4MonthsChargesOnly8Months() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime startedAt = LocalDateTime.now().minusMonths(1).withNano(0);
        LocalDateTime expiresAt = startedAt.plusMonths(4); // 4 months total from startedAt
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(startedAt)
                .expiresAt(expiresAt)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeSub));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_8_MONTHS")
                .transactionId("VNP_8_MONTHS")
                .status("PENDING")
                .build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION_YEARLY"),
                eq(2L), eq(new BigDecimal("8000000")), anyString(), any()))
                .thenReturn(paymentResponse);

        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .billingCycle(BillingCycle.YEARLY)
                .build();

        PaymentUrlResponse res = subscriptionService.subscribe(1L, request, "127.0.0.1");

        assertNotNull(res);
        assertEquals("VNP_8_MONTHS", res.getTransactionId());
        verify(paymentGatewayService).createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION_YEARLY"),
                eq(2L), eq(new BigDecimal("8000000")), anyString(), any());
    }

    @Test
    @DisplayName("subscribe - Renewing active plan that already reached 12 months from startedAt throws AppException")
    void testSubscribeSameActivePlanWhenAlreadyAt12MonthsCapThrowsException() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime startedAt = LocalDateTime.now().minusMonths(2).withNano(0);
        LocalDateTime expiresAt = startedAt.plusMonths(12); // already at 12-month cap
        CompanySubscription activeSub = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(startedAt)
                .expiresAt(expiresAt)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeSub));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        SubscribeRequest monthlyReq = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .billingCycle(BillingCycle.MONTHLY)
                .build();

        assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, monthlyReq, "127.0.0.1"));

        SubscribeRequest yearlyReq = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .billingCycle(BillingCycle.YEARLY)
                .build();

        assertThrows(AppException.class, () -> subscriptionService.subscribe(1L, yearlyReq, "127.0.0.1"));
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("subscribe & activateSubscription - Upgrading from 12-month Basic to Yearly Pro charges full 12 months and sets new 12-month cycle from upgrade time")
    void testUpgradeFrom12MonthBasicToYearlyProChargesFullPriceAndStartsNew12MonthCycle() {
        proPlan.setBillingCycle(BillingCycle.MONTHLY);
        LocalDateTime oldStart = LocalDateTime.now().minusMonths(2).withNano(0);
        LocalDateTime oldExpiry = oldStart.plusMonths(12);
        CompanySubscription activeBasic12M = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(freePlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(oldStart)
                .expiresAt(oldExpiry)
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeBasic12M));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("/api/payment/mock-checkout?txnRef=VNP_FULL_12M")
                .transactionId("VNP_FULL_12M")
                .status("PENDING")
                .build();
        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("SUBSCRIPTION_YEARLY"),
                eq(2L), eq(new BigDecimal("12000000")), anyString(), any()))
                .thenReturn(paymentResponse);

        SubscribeRequest upgradeReq = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .billingCycle(BillingCycle.YEARLY)
                .build();

        PaymentUrlResponse res = subscriptionService.subscribe(1L, upgradeReq, "127.0.0.1");
        assertEquals("VNP_FULL_12M", res.getTransactionId());

        subscriptionService.activateSubscription(1L, 2L, BillingCycle.YEARLY);
        assertTrue(activeBasic12M.getStartedAt().isAfter(oldStart));
        assertEquals(activeBasic12M.getStartedAt().plusYears(1), activeBasic12M.getExpiresAt());
        verify(creditService).resetBalance(1L);
        verify(creditService).grantMonthlyCredits(1L, 200);
    }

    @Test
    @DisplayName("Orphan Subscription entity - Removed from billing entity package")
    void testOrphanSubscriptionEntityRemoved() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("fu.se184491.loadmaster_be.entity.billing.Subscription"),
                "Orphan entity fu.se184491.loadmaster_be.entity.billing.Subscription should be deleted");
    }

    @Test
    @DisplayName("autoRenewExpiringSubscriptions - free plan renewal grants credits with a reference tied to the ended period")
    void autoRenewFreePlanGrantsOncePerPeriod() {
        LocalDateTime endedAt = LocalDateTime.now().minusMinutes(5).withNano(0);
        CompanySubscription sub = CompanySubscription.builder()
                .id(7L).company(company).plan(freePlan).status(SubscriptionStatus.ACTIVE)
                .startedAt(endedAt.minusMonths(1)).expiresAt(endedAt).autoRenew(true).build();
        when(companySubscriptionRepository.findByStatusAndExpiresAtBefore(eq(SubscriptionStatus.ACTIVE), any()))
                .thenReturn(java.util.List.of(sub));

        subscriptionService.autoRenewExpiringSubscriptions();

        verify(creditService).grantMonthlyCredits(1L, 50, "RENEW-7-" + endedAt);
        assertTrue(sub.getExpiresAt().isAfter(endedAt));
    }

    @Test
    @DisplayName("RENEWAL_WINDOW_DAYS - a plan can be renewed in its last 7 days")
    void renewalWindowIsSevenDays() {
        assertEquals(7, SubscriptionServiceImpl.RENEWAL_WINDOW_DAYS);
    }
}
