package fu.se184491.loadmaster_be.service.credit;

import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import fu.se184491.loadmaster_be.entity.billing.CreditTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditTransactionRepository;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CreditServiceTest {

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

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
    @DisplayName("Get balance should return balance from credit account")
    void getBalance_success() {
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(5, response.getBalance());
        assertFalse(response.getIsUnlimited());
    }

    @Test
    @DisplayName("Get balance on ULTIMATE plan should return isUnlimited = false (uses 30,000 credit pool)")
    void getBalance_ultimatePlan() {
        subscription.setPlan(ultimatePlan);
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(5, response.getBalance());
        assertFalse(response.getIsUnlimited());
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
        creditAccount.setBalance(1);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));

        assertThrows(InsufficientCreditsException.class, () ->
                creditService.deductCredit(1L, "job-uuid-123")
        );

        verify(creditAccountRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deduct credit on ULTIMATE subscription should also deduct from credit pool")
    void deductCredit_ultimatePlan_deductsFromPool() {
        subscription.setPlan(ultimatePlan);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(creditAccountRepository.save(any(CreditAccount.class))).thenReturn(creditAccount);

        creditService.deductCredit(1L, "job-uuid-123");

        assertEquals(3, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == -2 &&
                tx.getType() == CreditTransactionType.USAGE
        ));
    }

    @Test
    @DisplayName("Refund credit should refund exact deducted amount (2 credits for initial run) if not already refunded")
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
    @DisplayName("Get balance when subscription is EXPIRED should reset balance to 0 and return 0")
    void getBalance_whenSubscriptionExpired_resetsBalanceToZero() {
        creditAccount.setBalance(6000);
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        CreditBalanceResponse response = creditService.getBalance(1L);

        assertNotNull(response);
        assertEquals(0, response.getBalance());
        assertEquals(0, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
    }

    @Test
    @DisplayName("Deduct credit when subscription is EXPIRED should reset balance to 0 and throw InsufficientCreditsException")
    void deductCredit_whenSubscriptionExpired_resetsBalanceAndThrowsInsufficientCredits() {
        creditAccount.setBalance(6000);
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        when(creditAccountRepository.findByCompanyIdForUpdate(1L)).thenReturn(Optional.of(creditAccount));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(subscription));

        assertThrows(InsufficientCreditsException.class, () ->
                creditService.deductCredit(1L, "job-uuid-expired")
        );

        assertEquals(0, creditAccount.getBalance());
        verify(creditAccountRepository).save(creditAccount);
    }
}
