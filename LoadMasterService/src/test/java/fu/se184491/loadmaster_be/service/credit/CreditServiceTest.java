package fu.se184491.loadmaster_be.service.credit;

import com.fasterxml.jackson.annotation.JsonFormat;
import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import fu.se184491.loadmaster_be.entity.billing.CreditTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditTransactionRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CreditServiceTest {

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private PaymentGatewayService paymentGatewayService;

    @Mock
    private fu.se184491.loadmaster_be.repository.common.AuditLogRepository auditLogRepository;

    @InjectMocks
    private CreditServiceImpl creditService;

    private Company company;
    private CreditAccount creditAccount;
    private CompanySubscription subscription;
    private SubscriptionPlan proPlan;
    private SubscriptionPlan ultimatePlan;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).build();
        creditAccount = CreditAccount.builder().id(10L).company(company).balance(5).build();

        proPlan = SubscriptionPlan.builder().id(1L).tier(SubscriptionTier.PRO).build();
        ultimatePlan = SubscriptionPlan.builder().id(2L).tier(SubscriptionTier.ULTIMATE).build();

        subscription = CompanySubscription.builder()
                .id(1L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("CreditTransaction & CreditTransactionResponse - createdAt uses yyyy-MM-dd HH:mm:ss format, DB Check constraint, and truncates to seconds")
    void creditTransactionCreatedAtFormattedAsSeconds() throws Exception {
        Field createdAtField = CreditTransaction.class.getDeclaredField("createdAt");
        Convert convert = createdAtField.getAnnotation(Convert.class);
        assertNotNull(convert, "CreditTransaction.createdAt must have @Convert");
        assertEquals(SupportTicket.LocalDateTimeSecondsConverter.class, convert.converter());
        assertNotNull(createdAtField.getAnnotation(Check.class), "CreditTransaction.createdAt must have @Check constraint");

        Field dtoCreatedAt = CreditTransactionResponse.class.getDeclaredField("createdAt");
        JsonFormat jsonFormat = dtoCreatedAt.getAnnotation(JsonFormat.class);
        assertNotNull(jsonFormat, "CreditTransactionResponse.createdAt must have @JsonFormat");
        assertEquals("yyyy-MM-dd HH:mm:ss", jsonFormat.pattern());

        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditAccountRepository.save(any(CreditAccount.class))).thenReturn(creditAccount);

        ArgumentCaptor<CreditTransaction> captor = ArgumentCaptor.forClass(CreditTransaction.class);
        creditService.deductCredit(1L, "job-fmt-test");

        verify(creditTransactionRepository).save(captor.capture());
        CreditTransaction savedTx = captor.getValue();
        assertNotNull(savedTx.getCreatedAt());
        assertEquals(0, savedTx.getCreatedAt().getNano(), "CreditTransaction.createdAt must be truncated to seconds");
    }

    @Test
    @DisplayName("Get balance should return balance from credit account")
    void getBalance_success() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(5, response.getBalance());
        assertFalse(response.getIsUnlimited());
    }

    @Test
    @DisplayName("Get balance on an ACTIVE ULTIMATE plan is unlimited; once expired it is not")
    void getBalance_ultimatePlan() {
        subscription.setPlan(ultimatePlan);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(5, response.getBalance());
        assertTrue(response.getIsUnlimited());

        subscription.setStatus(SubscriptionStatus.EXPIRED);
        assertFalse(creditService.getBalance(1L).getIsUnlimited());
    }

    @Test
    @DisplayName("Deduct credit (initial run) should decrease balance by 2 and create USAGE transaction with -2")
    void deductCredit_success() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditAccountRepository.save(any(CreditAccount.class))).thenReturn(creditAccount);

        creditService.deductCredit(1L, "job-uuid-123");

        assertEquals(3, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == -2 &&
                tx.getType() == CreditTransactionType.USAGE &&
                "job-uuid-123".equals(tx.getReference())
        ));
    }

    @Test
    @DisplayName("Deduct credit with custom amount (rerun = 1 credit) should decrease balance by 1")
    void deductCredit_rerunSingleCredit_success() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditAccountRepository.save(any(CreditAccount.class))).thenReturn(creditAccount);

        creditService.deductCredit(1L, "job-rerun-123", 1);

        assertEquals(4, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == -1 &&
                tx.getType() == CreditTransactionType.USAGE &&
                "job-rerun-123".equals(tx.getReference())
        ));
    }

    @Test
    @DisplayName("Deduct credit should throw InsufficientCreditsException when balance is less than required amount")
    void deductCredit_insufficientCredits() {
        creditAccount.setBalance(0);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        assertThrows(InsufficientCreditsException.class, () ->
                creditService.deductCredit(1L, "job-uuid-123")
        );

        verify(creditAccountRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deduct credit on an ACTIVE ULTIMATE subscription records a 0-credit usage and keeps the balance")
    void deductCredit_ultimatePlan_isUnlimited() {
        subscription.setPlan(ultimatePlan);
        creditAccount.setBalance(0);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        creditService.deductCredit(1L, "job-uuid-123");

        assertEquals(0, creditAccount.getBalance());
        verify(creditAccountRepository, never()).save(any());
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == 0 &&
                tx.getType() == CreditTransactionType.USAGE &&
                "job-uuid-123".equals(tx.getReference())
        ));
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deduct credit takes the account lock before looking up the reference, and skips a duplicate")
    void deductCredit_locksBeforeCheckingTheReference() {
        CreditTransaction existing = CreditTransaction.builder()
                .creditAccount(creditAccount).amount(-2).type(CreditTransactionType.USAGE).reference("job-dup").build();
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditTransactionRepository.findByReferenceAndType("job-dup", CreditTransactionType.USAGE))
                .thenReturn(Optional.of(existing));

        creditService.deductCredit(1L, "job-dup");

        var order = inOrder(creditAccountRepository, creditTransactionRepository);
        order.verify(creditAccountRepository).findByCompanyIdForUpdate(1L);
        order.verify(creditTransactionRepository).findByReferenceAndType("job-dup", CreditTransactionType.USAGE);
        assertEquals(5, creditAccount.getBalance());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refunding a 0-credit (unlimited) usage marks it refunded without changing the balance")
    void refundCredit_zeroUsage() {
        CreditTransaction usage = CreditTransaction.builder().id(98L).creditAccount(creditAccount).amount(0)
                .type(CreditTransactionType.USAGE).reference("job-u").refunded(false).build();
        when(creditTransactionRepository.findByReferenceAndType("job-u", CreditTransactionType.USAGE))
                .thenReturn(Optional.of(usage));
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        creditService.refundCredit(1L, "job-u");

        assertTrue(usage.getRefunded());
        assertEquals(5, creditAccount.getBalance());
        verify(creditTransactionRepository, never()).save(argThat(tx -> tx.getType() == CreditTransactionType.REFUND));
    }

    @Test
    @DisplayName("Low-credit warning fires once, when the balance first crosses 25% of the plan's monthly credits")
    void lowBalanceWarningFiresOnCrossing() {
        proPlan.setMonthlyCredits(10);  // ngưỡng ceil(2.5) = 3
        creditAccount.setBalance(4);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        creditService.deductCredit(1L, "job-a", 1);   // 4 -> 3: vừa qua ngưỡng
        creditService.deductCredit(1L, "job-b", 1);   // 3 -> 2: đã dưới ngưỡng, không báo lại

        verify(auditLogRepository, times(1)).save(argThat(log ->
                "CREDIT_LOW_BALANCE".equals(log.getActionType())
                        && Integer.valueOf(3).equals(log.getNewValues().get("threshold"))));
    }

    @Test
    @DisplayName("Monthly grant and purchase with an already recorded reference are skipped")
    void grantAndPurchaseAreIdempotentPerReference() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditTransactionRepository.findByReferenceAndType("RENEW-1", CreditTransactionType.MONTHLY_GRANT))
                .thenReturn(Optional.of(CreditTransaction.builder().build()));
        when(creditTransactionRepository.findByReferenceAndType("VNP-1", CreditTransactionType.PURCHASE))
                .thenReturn(Optional.of(CreditTransaction.builder().build()));

        creditService.grantMonthlyCredits(1L, 100, "RENEW-1");
        creditService.addCredits(1L, 50, "VNP-1");

        assertEquals(5, creditAccount.getBalance());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refund credit should refund exact deducted amount of the original USAGE if not already refunded")
    void refundCredit_success() {
        CreditTransaction originalUsage = CreditTransaction.builder()
                .id(99L)
                .creditAccount(creditAccount)
                .amount(-2)
                .type(CreditTransactionType.USAGE)
                .reference("job-uuid-123")
                .refunded(false)
                .build();

        when(creditTransactionRepository.findByReferenceAndType("job-uuid-123", CreditTransactionType.USAGE))
                .thenReturn(Optional.of(originalUsage));
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        creditService.refundCredit(1L, "job-uuid-123");

        assertTrue(originalUsage.getRefunded());
        assertEquals(7, creditAccount.getBalance());
        verify(creditTransactionRepository).save(originalUsage);
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == 2 &&
                tx.getType() == CreditTransactionType.REFUND &&
                "job-uuid-123".equals(tx.getReference())
        ));
    }

    @Test
    @DisplayName("Refund credit should do nothing if already refunded")
    void refundCredit_alreadyRefunded() {
        CreditTransaction alreadyRefunded = CreditTransaction.builder()
                .id(99L)
                .creditAccount(creditAccount)
                .amount(-1)
                .type(CreditTransactionType.USAGE)
                .reference("job-uuid-123")
                .refunded(true)
                .build();

        when(creditTransactionRepository.findByReferenceAndType("job-uuid-123", CreditTransactionType.USAGE))
                .thenReturn(Optional.of(alreadyRefunded));

        creditService.refundCredit(1L, "job-uuid-123");

        verify(creditAccountRepository, never()).findByCompanyIdForUpdate(any());
    }

    @Test
    @DisplayName("Grant monthly credits should increase balance and log transaction")
    void grantMonthlyCredits_success() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        creditService.grantMonthlyCredits(1L, 100);

        assertEquals(105, creditAccount.getBalance());
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == 100 && tx.getType() == CreditTransactionType.MONTHLY_GRANT
        ));
    }

    @Test
    @DisplayName("Get balance when subscription is EXPIRED keeps the balance")
    void getBalance_whenSubscriptionExpired_keepsBalance() {
        creditAccount.setBalance(6000);
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(6000, response.getBalance());
        verify(creditAccountRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deduct credit when subscription is EXPIRED spends the kept balance; too little still throws InsufficientCredits")
    void deductCredit_whenSubscriptionExpired_spendsKeptBalance() {
        creditAccount.setBalance(6000);
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscription.setPlan(ultimatePlan);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        creditService.deductCredit(1L, "job-uuid-expired");
        assertEquals(5998, creditAccount.getBalance());

        creditAccount.setBalance(1);
        assertThrows(InsufficientCreditsException.class, () ->
                creditService.deductCredit(1L, "job-uuid-expired-2")
        );
        assertEquals(1, creditAccount.getBalance());
    }

    @Test
    @DisplayName("Reset balance should set credit account balance to 0 even when subscription is ACTIVE")
    void resetBalance_setsBalanceToZero() {
        creditAccount.setBalance(2400);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        creditService.resetBalance(1L);

        assertEquals(0, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
    }

    @Test
    @DisplayName("Get balance when entering a new billing month of an active multi-month subscription resets balance back to plan monthlyCredits")
    void getBalance_whenNewBillingMonthStartedInMultiMonthSubscription_resetsBalanceBackToPlanMonthlyCredits() {
        proPlan.setMonthlyCredits(10000);
        subscription.setStartedAt(java.time.LocalDateTime.now().minusMonths(1).minusDays(1).withNano(0));
        subscription.setExpiresAt(subscription.getStartedAt().plusMonths(4));
        creditAccount.setBalance(14500); // leftover from previous month including top-up

        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));
        when(creditTransactionRepository.findByReferenceAndType("CYCLE-1-M2", CreditTransactionType.MONTHLY_GRANT))
                .thenReturn(Optional.empty());

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(10000, response.getBalance());
        assertEquals(10000, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == 10000
                        && tx.getType() == CreditTransactionType.MONTHLY_GRANT
                        && "CYCLE-1-M2".equals(tx.getReference())
        ));
    }

    @Test
    @DisplayName("Topup in CreditServiceImpl holds PRICE_PER_CREDIT constants (400 / 326 / 263) and charges by tier without cumulative cap")
    void topup_chargesByTierPriceInServiceImplWithoutCumulativeCap() {
        assertEquals(new BigDecimal("400"), CreditServiceImpl.PRICE_PER_CREDIT_BASIC_VND);
        assertEquals(new BigDecimal("326"), CreditServiceImpl.PRICE_PER_CREDIT_PRO_VND);
        assertEquals(new BigDecimal("263"), CreditServiceImpl.PRICE_PER_CREDIT_ULTIMATE_VND);

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        // BASIC tier -> 400 VND * 5000 = 2,000,000 VND (no 3000 cap)
        SubscriptionPlan basicPlan = SubscriptionPlan.builder().id(1L).tier(SubscriptionTier.BASIC).build();
        subscription.setPlan(basicPlan);
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));
        when(paymentGatewayService.createPayment(
                eq(company),
                eq(PaymentGateway.MOCK),
                eq("CREDIT_TOPUP"),
                eq(5000L),
                eq(new BigDecimal("2000000")),
                anyString(),
                eq("127.0.0.1")
        )).thenReturn(PaymentUrlResponse.builder().transactionId("MOCK_BASIC_5000").status("PENDING").build());

        CreditTopupRequest req = CreditTopupRequest.builder().credits(5000).gateway(PaymentGateway.MOCK).build();
        PaymentUrlResponse basicRes = creditService.topup(1L, req, "127.0.0.1");
        assertEquals("MOCK_BASIC_5000", basicRes.getTransactionId());
    }

    @Test
    @DisplayName("topup - gói đã hết hạn hoặc đã huỷ trả mã SUBSCRIPTION_EXPIRED, không tạo thanh toán")
    void topupOnExpiredOrCancelledSubscriptionIsSubscriptionExpired() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));
        CreditTopupRequest req = CreditTopupRequest.builder().credits(10).gateway(PaymentGateway.MOCK).build();

        for (SubscriptionStatus status : new SubscriptionStatus[] {SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED}) {
            subscription.setStatus(status);
            fu.se184491.loadmaster_be.exception.AppException error = assertThrows(
                    fu.se184491.loadmaster_be.exception.AppException.class,
                    () -> creditService.topup(1L, req, "127.0.0.1"));
            assertEquals(fu.se184491.loadmaster_be.exception.ErrorCode.SUBSCRIPTION_EXPIRED, error.getErrorCode());
        }
        verifyNoInteractions(paymentGatewayService);
    }

    @Test
    @DisplayName("getPricing - giá theo hạng gói đang dùng, một lần tối ưu 2 credit, chạy lại 1 credit, nạp từ 1 credit không trần")
    void pricingFollowsTheCurrentTier() {
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        var pricing = creditService.getPricing(1L);

        assertEquals(SubscriptionTier.PRO, pricing.getTier());
        assertEquals(new BigDecimal("326"), pricing.getPricePerCreditVnd());
        assertEquals(new BigDecimal("400"), pricing.getTierPrices().get(SubscriptionTier.BASIC));
        assertEquals(new BigDecimal("263"), pricing.getTierPrices().get(SubscriptionTier.ULTIMATE));
        assertEquals(2, pricing.getOptimizationCost());
        assertEquals(1, pricing.getRerunCost());
        assertEquals(1, pricing.getMinTopUpCredits());
        assertNull(pricing.getMaxTopUpCredits());
        assertFalse(pricing.getUnlimited());
        assertTrue(pricing.getTopUpAllowed());
        assertNull(pricing.getLowBalanceThreshold(), "gói không ghi credit tháng thì không có ngưỡng");

        proPlan.setMonthlyCredits(10000);
        assertEquals(2500, creditService.getPricing(1L).getLowBalanceThreshold());
        proPlan.setMonthlyCredits(3001);
        assertEquals(751, creditService.getPricing(1L).getLowBalanceThreshold());
    }

    @Test
    @DisplayName("getPricing - chưa có gói tính như Basic, nạp được; gói hết hạn không nạp được; Ultimate đang dùng là không giới hạn")
    void pricingWithoutPlanExpiredAndUltimate() {
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());
        var none = creditService.getPricing(1L);
        assertEquals(SubscriptionTier.BASIC, none.getTier());
        assertEquals(new BigDecimal("400"), none.getPricePerCreditVnd());
        assertTrue(none.getTopUpAllowed());

        subscription.setStatus(SubscriptionStatus.EXPIRED);
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));
        assertFalse(creditService.getPricing(1L).getTopUpAllowed());

        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setPlan(ultimatePlan);
        var ultimate = creditService.getPricing(1L);
        assertTrue(ultimate.getUnlimited());
        assertNull(ultimate.getLowBalanceThreshold());
        assertNull(none.getLowBalanceThreshold());
        assertEquals(new BigDecimal("263"), ultimate.getPricePerCreditVnd());
    }

    @Test
    @DisplayName("ensureAccount tạo tài khoản số dư 0 khi công ty chưa có")
    void ensureAccount_createsMissingAccount() {
        when(creditAccountRepository.existsByCompanyId(1L)).thenReturn(false);
        when(companyRepository.existsById(1L)).thenReturn(true);
        when(creditAccountRepository.insertIfMissing(1L)).thenReturn(1);

        creditService.ensureAccount(1L);

        verify(creditAccountRepository).insertIfMissing(1L);
        verify(creditAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("ensureAccount không đổi gì khi tài khoản đã có")
    void ensureAccount_existingAccountIsLeftAlone() {
        when(creditAccountRepository.existsByCompanyId(1L)).thenReturn(true);

        creditService.ensureAccount(1L);

        verify(creditAccountRepository, never()).insertIfMissing(anyLong());
        verify(creditAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("ensureAccount với công ty không tồn tại là COMPANY_NOT_FOUND, không chèn gì")
    void ensureAccount_unknownCompany() {
        when(creditAccountRepository.existsByCompanyId(99L)).thenReturn(false);
        when(companyRepository.existsById(99L)).thenReturn(false);

        assertThrows(fu.se184491.loadmaster_be.exception.AppException.class, () -> creditService.ensureAccount(99L));
        verify(creditAccountRepository, never()).insertIfMissing(anyLong());
    }

    @Test
    @DisplayName("Cấp credit cho công ty chưa có tài khoản: chèn bằng insertIfMissing rồi khoá lại dòng vừa tạo")
    void grantMonthlyCredits_createsAccountWithoutSaveRace() {
        when(creditAccountRepository.findByCompanyIdForUpdate(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(CreditAccount.builder().id(11L).company(company).balance(0).build()));
        when(creditAccountRepository.existsByCompanyId(1L)).thenReturn(false);
        when(companyRepository.existsById(1L)).thenReturn(true);
        when(creditAccountRepository.insertIfMissing(1L)).thenReturn(1);

        creditService.grantMonthlyCredits(1L, 3000);

        verify(creditAccountRepository).insertIfMissing(1L);
        verify(creditAccountRepository).save(argThat(acc -> acc.getBalance() == 3000));
    }
}
