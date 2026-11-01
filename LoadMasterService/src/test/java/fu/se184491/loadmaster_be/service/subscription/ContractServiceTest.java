package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.CreateContractRequest;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditTransactionRepository;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.subscription.Impl.ContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @InjectMocks
    private ContractServiceImpl contractService;

    private Company company;
    private SubscriptionPlan basicPlan;
    private SubscriptionPlan proPlan;
    private SubscriptionPlan ultimatePlan;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(1L)
                .companyCode("LM-01")
                .companyName("Acme Logistics")
                .build();

        basicPlan = SubscriptionPlan.builder()
                .id(1L)
                .planCode("BASIC_MONTHLY")
                .name("Basic")
                .tier(SubscriptionTier.BASIC)
                .price(new BigDecimal("1200000"))
                .monthlyCredits(3000)
                .billingCycle(BillingCycle.MONTHLY)
                .active(true)
                .build();

        proPlan = SubscriptionPlan.builder()
                .id(2L)
                .planCode("PRO_MONTHLY")
                .name("Pro")
                .tier(SubscriptionTier.PRO)
                .price(new BigDecimal("4900000"))
                .monthlyCredits(10000)
                .billingCycle(BillingCycle.MONTHLY)
                .active(true)
                .build();

        ultimatePlan = SubscriptionPlan.builder()
                .id(3L)
                .planCode("ULTIMATE_MONTHLY")
                .name("Ultimate")
                .tier(SubscriptionTier.ULTIMATE)
                .price(new BigDecimal("7900000"))
                .monthlyCredits(30000)
                .billingCycle(BillingCycle.MONTHLY)
                .active(true)
                .build();

        lenient().when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        lenient().when(companySubscriptionRepository.save(any(CompanySubscription.class)))
                .thenAnswer(inv -> {
                    CompanySubscription s = inv.getArgument(0);
                    if (s.getId() == null) s.setId(10L);
                    return s;
                });
    }

    @Test
    @DisplayName("Upgrading active Basic (3000) with remaining credits to Pro (10000) resets balance to 0 and grants 10000")
    void createOrActivateContract_activeBasicToPro_resetsBalanceToNewPlanCredits() {
        CompanySubscription activeBasic = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(basicPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(10))
                .expiresAt(LocalDateTime.now().plusDays(20))
                .autoRenew(true)
                .build();

        CreditAccount creditAccount = CreditAccount.builder()
                .id(1L)
                .company(company)
                .balance(2400)
                .build();

        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activeBasic));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        CreateContractRequest req = new CreateContractRequest();
        req.setCompanyId(1L);
        req.setPlanId(2L);
        req.setAutoRenew(true);

        contractService.createOrActivateContract(req);

        assertEquals(10000, creditAccount.getBalance());
    }

    @Test
    @DisplayName("Upgrading active Pro (10000) with remaining credits to Ultimate (30000) resets balance to 0 and grants 30000")
    void createOrActivateContract_activeProToUltimate_resetsBalanceToNewPlanCredits() {
        CompanySubscription activePro = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(5))
                .expiresAt(LocalDateTime.now().plusDays(25))
                .autoRenew(true)
                .build();

        CreditAccount creditAccount = CreditAccount.builder()
                .id(1L)
                .company(company)
                .balance(8500)
                .build();

        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activePro));
        when(subscriptionPlanRepository.findById(3L)).thenReturn(Optional.of(ultimatePlan));
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        CreateContractRequest req = new CreateContractRequest();
        req.setCompanyId(1L);
        req.setPlanId(3L);
        req.setAutoRenew(true);

        contractService.createOrActivateContract(req);

        assertEquals(30000, creditAccount.getBalance());
    }

    @Test
    @DisplayName("Upgrading EXPIRED subscription resets balance to 0 and grants only new plan monthlyCredits (10000 for Pro)")
    void createOrActivateContract_expiredSubscription_discardsOldCredits() {
        CompanySubscription expiredBasic = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(basicPlan)
                .status(SubscriptionStatus.EXPIRED)
                .startedAt(LocalDateTime.now().minusDays(40))
                .expiresAt(LocalDateTime.now().minusDays(10))
                .autoRenew(false)
                .build();

        CreditAccount creditAccount = CreditAccount.builder()
                .id(1L)
                .company(company)
                .balance(600)
                .build();

        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(expiredBasic));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        CreateContractRequest req = new CreateContractRequest();
        req.setCompanyId(1L);
        req.setPlanId(2L);
        req.setAutoRenew(true);

        contractService.createOrActivateContract(req);

        assertEquals(10000, creditAccount.getBalance());
    }

    @Test
    @DisplayName("SystemSupporter cannot downgrade or re-apply same plan when company subscription is ACTIVE")
    void createOrActivateContract_activeProToBasicOrSamePro_throwsException() {
        CompanySubscription activePro = CompanySubscription.builder()
                .id(10L)
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now().minusDays(5))
                .expiresAt(LocalDateTime.now().plusDays(25))
                .autoRenew(true)
                .build();

        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(activePro));
        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(basicPlan));
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));

        CreateContractRequest downgradeReq = new CreateContractRequest();
        downgradeReq.setCompanyId(1L);
        downgradeReq.setPlanId(1L);
        downgradeReq.setAutoRenew(true);

        org.junit.jupiter.api.Assertions.assertThrows(
                fu.se184491.loadmaster_be.exception.AppException.class,
                () -> contractService.createOrActivateContract(downgradeReq)
        );

        CreateContractRequest samePlanReq = new CreateContractRequest();
        samePlanReq.setCompanyId(1L);
        samePlanReq.setPlanId(2L);
        samePlanReq.setAutoRenew(true);

        org.junit.jupiter.api.Assertions.assertThrows(
                fu.se184491.loadmaster_be.exception.AppException.class,
                () -> contractService.createOrActivateContract(samePlanReq)
        );
    }
}
