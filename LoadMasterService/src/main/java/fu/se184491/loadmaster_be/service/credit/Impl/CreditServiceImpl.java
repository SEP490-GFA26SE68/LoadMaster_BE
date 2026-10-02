package fu.se184491.loadmaster_be.service.credit.Impl;

import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.entity.billing.CreditAccount;
import fu.se184491.loadmaster_be.entity.billing.CreditTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditTransactionRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditServiceImpl implements CreditService {

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CompanyRepository companyRepository;

    @Override
    @Transactional(readOnly = true)
    public CreditBalanceResponse getBalance(Long companyId) {
        CreditAccount account = getOrCreateAccount(companyId);
        boolean isUnlimited = isUnlimitedPlan(companyId);

        return CreditBalanceResponse.builder()
                .companyId(companyId)
                .balance(account.getBalance())
                .isUnlimited(isUnlimited)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditTransactionResponse> getTransactions(Long companyId, Pageable pageable) {
        CreditAccount account = getOrCreateAccount(companyId);
        return creditTransactionRepository.findByCreditAccountIdOrderByCreatedAtDesc(account.getId(), pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional
    public void deductCredit(Long companyId, String reference) {
        if (isUnlimitedPlan(companyId)) {
            log.info("Company {} has ULTIMATE plan. Skipping credit deduction for reference {}", companyId, reference);
            return;
        }

        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> getOrCreateAccount(companyId));

        if (account.getBalance() <= 0) {
            log.warn("Company {} has insufficient credits (balance: {}) for reference {}", companyId, account.getBalance(), reference);
            throw new InsufficientCreditsException();
        }

        account.setBalance(account.getBalance() - 1);
        creditAccountRepository.save(account);

        CreditTransaction tx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(-1)
                .type(CreditTransactionType.USAGE)
                .reference(reference)
                .refunded(false)
                .createdAt(LocalDateTime.now())
                .build();
        creditTransactionRepository.save(tx);
        log.info("Deducted 1 credit for company {}. New balance: {}", companyId, account.getBalance());
    }

    @Override
    @Transactional
    public void refundCredit(Long companyId, String reference) {
        Optional<CreditTransaction> originalUsageOpt = creditTransactionRepository
                .findByReferenceAndType(reference, CreditTransactionType.USAGE);

        if (originalUsageOpt.isEmpty()) {
            log.warn("No USAGE transaction found for reference {}. Skipping refund.", reference);
            return;
        }

        CreditTransaction originalUsage = originalUsageOpt.get();
        if (Boolean.TRUE.equals(originalUsage.getRefunded())) {
            log.info("Reference {} already refunded. Skipping duplicate refund.", reference);
            return;
        }

        originalUsage.setRefunded(true);
        creditTransactionRepository.save(originalUsage);

        Long targetCompanyId = companyId != null ? companyId :
                (originalUsage.getCreditAccount() != null && originalUsage.getCreditAccount().getCompany() != null
                        ? originalUsage.getCreditAccount().getCompany().getId()
                        : null);

        if (targetCompanyId == null) {
            log.warn("Cannot determine companyId for refund on reference {}. Skipping.", reference);
            return;
        }

        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(targetCompanyId)
                .orElseGet(() -> getOrCreateAccount(targetCompanyId));
        account.setBalance(account.getBalance() + 1);
        creditAccountRepository.save(account);

        CreditTransaction refundTx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(1)
                .type(CreditTransactionType.REFUND)
                .reference(reference)
                .refunded(false)
                .createdAt(LocalDateTime.now())
                .build();
        creditTransactionRepository.save(refundTx);
        log.info("Refunded 1 credit for company {} on reference {}. New balance: {}", companyId, reference, account.getBalance());
    }

    @Override
    @Transactional
    public void grantMonthlyCredits(Long companyId, int amount) {
        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> getOrCreateAccount(companyId));
        account.setBalance(account.getBalance() + amount);
        creditAccountRepository.save(account);

        CreditTransaction tx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(amount)
                .type(CreditTransactionType.MONTHLY_GRANT)
                .createdAt(LocalDateTime.now())
                .build();
        creditTransactionRepository.save(tx);
        log.info("Granted {} monthly credits to company {}. New balance: {}", amount, companyId, account.getBalance());
    }

    @Override
    @Transactional
    public void addCredits(Long companyId, int amount, String reference) {
        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> getOrCreateAccount(companyId));
        account.setBalance(account.getBalance() + amount);
        creditAccountRepository.save(account);

        CreditTransaction tx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(amount)
                .type(CreditTransactionType.PURCHASE)
                .reference(reference)
                .createdAt(LocalDateTime.now())
                .build();
        creditTransactionRepository.save(tx);
        log.info("Purchased/Added {} credits to company {}. New balance: {}", amount, companyId, account.getBalance());
    }

    private CreditAccount getOrCreateAccount(Long companyId) {
        return creditAccountRepository.findByCompanyId(companyId)
                .orElseGet(() -> {
                    Company company = companyRepository.findById(companyId)
                            .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
                    CreditAccount newAccount = CreditAccount.builder()
                            .company(company)
                            .balance(0)
                            .build();
                    return creditAccountRepository.save(newAccount);
                });
    }

    private boolean isUnlimitedPlan(Long companyId) {
        return companySubscriptionRepository.findByCompanyIdAndStatus(companyId, SubscriptionStatus.ACTIVE)
                .map(sub -> sub.getPlan() != null && sub.getPlan().getTier() == SubscriptionTier.ULTIMATE)
                .orElse(false);
    }

    private CreditTransactionResponse toResponse(CreditTransaction tx) {
        return CreditTransactionResponse.builder()
                .id(tx.getId())
                .amount(tx.getAmount())
                .type(tx.getType())
                .reference(tx.getReference())
                .refunded(tx.getRefunded())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
