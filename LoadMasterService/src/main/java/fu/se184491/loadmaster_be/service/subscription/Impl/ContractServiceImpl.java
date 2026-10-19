package fu.se184491.loadmaster_be.service.subscription.Impl;

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
import fu.se184491.loadmaster_be.service.subscription.ContractService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ContractServiceImpl implements ContractService {

    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanyRepository companyRepository;
    private final InvoiceRepository invoiceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ContractResponse> getContracts(Long companyId, SubscriptionStatus status, Long planId) {
        Specification<CompanySubscription> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (companyId != null) {
                predicates.add(cb.equal(root.get("company").get("id"), companyId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (planId != null) {
                predicates.add(cb.equal(root.get("plan").get("id"), planId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return companySubscriptionRepository.findAll(spec)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContractResponse getContractById(Long id) {
        CompanySubscription subscription = companySubscriptionRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_NOT_FOUND));
        return mapToResponse(subscription);
    }

    @Override
    public ContractResponse createOrActivateContract(CreateContractRequest request) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        CompanySubscription subscription = companySubscriptionRepository.findByCompanyId(company.getId())
                .orElseGet(() -> CompanySubscription.builder().company(company).build());

        boolean wasActiveAndInTerm = subscription.getId() != null
                && subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getExpiresAt() != null
                && subscription.getExpiresAt().isAfter(now);
        SubscriptionPlan previousPlan = subscription.getPlan();

        if (wasActiveAndInTerm && previousPlan != null) {
            int currentRank = tierRank(previousPlan.getTier());
            int targetRank = tierRank(plan.getTier());
            if (targetRank <= currentRank) {
                throw new AppException(ErrorCode.INVALID_INPUT,
                        "Khách hàng đang còn hạn gói " + previousPlan.getName()
                                + ". Chỉ được nâng cấp lên gói cao hơn khi gói hiện tại đang hoạt động.");
            }
        }

        LocalDateTime expiresAt = (plan.getBillingCycle() == BillingCycle.YEARLY)
                ? now.plusYears(1)
                : now.plusMonths(1);

        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartedAt(now);
        subscription.setExpiresAt(expiresAt);
        subscription.setAutoRenew(request.getAutoRenew() != null ? request.getAutoRenew() : true);

        CompanySubscription savedSubscription = companySubscriptionRepository.save(subscription);

        // 1. Sinh hóa đơn tự động với trạng thái PAID
        String invoiceNumber = "INV-" + company.getId() + "-" + System.currentTimeMillis();
        String period = request.getBillingPeriod() != null
                ? request.getBillingPeriod()
                : (plan.getBillingCycle() != null ? plan.getBillingCycle().name() : "CONTRACT");

        BigDecimal amount = plan.getPrice() != null ? plan.getPrice() : BigDecimal.ZERO;

        Invoice invoice = Invoice.builder()
                .company(company)
                .companySubscription(savedSubscription)
                .invoiceNumber(invoiceNumber)
                .billingPeriod(period)
                .subtotalAmount(amount)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(amount)
                .status(InvoiceStatus.PAID)
                .dueDate(now)
                .createdAt(Instant.now())
                .build();
        invoiceRepository.save(invoice);

        // 2. Khi đổi/kích hoạt gói: reset số dư credit về 0 rồi cộng đúng số credit định mức của gói mới
        if (plan.getMonthlyCredits() != null && plan.getMonthlyCredits() > 0) {
            CreditAccount creditAccount = creditAccountRepository.findByCompanyId(company.getId())
                    .orElseGet(() -> {
                        CreditAccount newAcc = CreditAccount.builder()
                                .company(company)
                                .balance(0)
                                .build();
                        return creditAccountRepository.save(newAcc);
                    });

            int newPlanCredits = plan.getMonthlyCredits();
            creditAccount.setBalance(newPlanCredits);
            creditAccountRepository.save(creditAccount);

            CreditTransaction transaction = CreditTransaction.builder()
                    .creditAccount(creditAccount)
                    .amount(newPlanCredits)
                    .type(CreditTransactionType.MONTHLY_GRANT)
                    .reference("CONTRACT-" + savedSubscription.getId())
                    .refunded(false)
                    .createdAt(now)
                    .build();
            creditTransactionRepository.save(transaction);
        }

        return mapToResponse(savedSubscription);
    }

    @Override
    public ContractResponse updateContract(Long id, UpdateContractRequest request) {
        CompanySubscription subscription = companySubscriptionRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_NOT_FOUND));

        if (request.getStatus() != null) {
            subscription.setStatus(request.getStatus());
        }
        if (request.getExpiresAt() != null) {
            subscription.setExpiresAt(request.getExpiresAt());
        }
        if (request.getAutoRenew() != null) {
            subscription.setAutoRenew(request.getAutoRenew());
        }

        CompanySubscription updated = companySubscriptionRepository.save(subscription);
        return mapToResponse(updated);
    }

    private ContractResponse mapToResponse(CompanySubscription entity) {
        return ContractResponse.builder()
                .id(entity.getId())
                .companyId(entity.getCompany() != null ? entity.getCompany().getId() : null)
                .companyCode(entity.getCompany() != null ? entity.getCompany().getCompanyCode() : null)
                .companyName(entity.getCompany() != null ? entity.getCompany().getCompanyName() : null)
                .planId(entity.getPlan() != null ? entity.getPlan().getId() : null)
                .planCode(entity.getPlan() != null ? entity.getPlan().getPlanCode() : null)
                .planName(entity.getPlan() != null ? entity.getPlan().getName() : null)
                .planTier(entity.getPlan() != null ? entity.getPlan().getTier() : null)
                .price(entity.getPlan() != null ? entity.getPlan().getPrice() : null)
                .billingCycle(entity.getPlan() != null ? entity.getPlan().getBillingCycle() : null)
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .expiresAt(entity.getExpiresAt())
                .autoRenew(entity.getAutoRenew())
                .build();
    }

    private int tierRank(SubscriptionTier tier) {
        if (tier == null) return 0;
        return switch (tier) {
            case BASIC -> 1;
            case PRO -> 2;
            case ULTIMATE -> 3;
        };
    }
}
