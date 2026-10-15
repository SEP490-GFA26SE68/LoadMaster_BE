package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.CreateContractRequest;
import fu.se184491.loadmaster_be.dto.request.subscription.UpdateContractRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.ContractResponse;
import fu.se184491.loadmaster_be.entity.billing.*;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.*;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    private SubscriptionPlan plan;
    private CreditAccount creditAccount;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(1L)
                .companyCode("COMP-001")
                .companyName("Công ty Vận Tải Ánh Dương")
                .build();

        plan = SubscriptionPlan.builder()
                .id(10L)
                .planCode("PLAN_PRO_YEARLY")
                .name("Gói Chuyên Nghiệp Năm")
                .tier(SubscriptionTier.PRO)
                .price(BigDecimal.valueOf(50000000))
                .billingCycle(BillingCycle.YEARLY)
                .monthlyCredits(1000)
                .build();

        creditAccount = CreditAccount.builder()
                .id(100L)
                .company(company)
                .balance(500)
                .build();
    }

    @Test
    @DisplayName("Kích hoạt hợp đồng: tạo mới CompanySubscription, sinh hóa đơn PAID, cộng CreditAccount và ghi CreditTransaction")
    void createOrActivateContract_Success() {
        CreateContractRequest request = CreateContractRequest.builder()
                .companyId(1L)
                .planId(10L)
                .autoRenew(true)
                .billingPeriod("2026-YEARLY")
                .build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(subscriptionPlanRepository.findById(10L)).thenReturn(Optional.of(plan));
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.empty());
        when(companySubscriptionRepository.save(any(CompanySubscription.class))).thenAnswer(invocation -> {
            CompanySubscription cs = invocation.getArgument(0);
            cs.setId(50L);
            return cs;
        });
        when(creditAccountRepository.findByCompanyId(1L)).thenReturn(Optional.of(creditAccount));

        ContractResponse response = contractService.createOrActivateContract(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(50L);
        assertThat(response.getCompanyId()).isEqualTo(1L);
        assertThat(response.getCompanyName()).isEqualTo("Công ty Vận Tải Ánh Dương");
        assertThat(response.getPlanCode()).isEqualTo("PLAN_PRO_YEARLY");
        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(response.getStartedAt()).isNotNull();
        assertThat(response.getExpiresAt()).isNotNull();

        // Kiểm tra hóa đơn tự động sinh với trạng thái PAID
        verify(invoiceRepository).save(argThat(inv ->
                inv.getCompany().getId().equals(1L) &&
                inv.getStatus() == InvoiceStatus.PAID &&
                inv.getTotalAmount().compareTo(BigDecimal.valueOf(50000000)) == 0
        ));

        // Kiểm tra cộng credit và lưu transaction
        assertThat(creditAccount.getBalance()).isEqualTo(1500); // 500 + 1000
        verify(creditTransactionRepository).save(argThat(tx ->
                tx.getAmount() == 1000 &&
                tx.getType() == CreditTransactionType.MONTHLY_GRANT
        ));
    }

    @Test
    @DisplayName("Lấy chi tiết hợp đồng thành công")
    void getContractById_Success() {
        CompanySubscription cs = CompanySubscription.builder()
                .id(50L)
                .company(company)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusYears(1))
                .autoRenew(true)
                .build();

        when(companySubscriptionRepository.findById(50L)).thenReturn(Optional.of(cs));

        ContractResponse response = contractService.getContractById(50L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(50L);
        assertThat(response.getCompanyCode()).isEqualTo("COMP-001");
    }

    @Test
    @DisplayName("Lấy chi tiết hợp đồng không tìm thấy -> ném CONTRACT_NOT_FOUND")
    void getContractById_NotFound() {
        when(companySubscriptionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contractService.getContractById(999L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONTRACT_NOT_FOUND);
    }

    @Test
    @DisplayName("Cập nhật hợp đồng thành công")
    void updateContract_Success() {
        CompanySubscription cs = CompanySubscription.builder()
                .id(50L)
                .company(company)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMonths(6))
                .autoRenew(true)
                .build();

        LocalDateTime newExpires = LocalDateTime.now().plusYears(1);
        UpdateContractRequest updateRequest = UpdateContractRequest.builder()
                .status(SubscriptionStatus.CANCELLED)
                .expiresAt(newExpires)
                .autoRenew(false)
                .build();

        when(companySubscriptionRepository.findById(50L)).thenReturn(Optional.of(cs));
        when(companySubscriptionRepository.save(any(CompanySubscription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContractResponse response = contractService.updateContract(50L, updateRequest);

        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(response.getExpiresAt()).isEqualTo(newExpires);
        assertThat(response.getAutoRenew()).isFalse();
    }
}
