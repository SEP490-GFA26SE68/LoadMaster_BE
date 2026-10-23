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

    public static final int INITIAL_OPTIMIZATION_CREDIT_COST = 2;
    public static final int RERUN_OPTIMIZATION_CREDIT_COST = 1;

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CompanyRepository companyRepository;

    @Override
    @Transactional
    public CreditBalanceResponse getBalance(Long companyId) {
        CreditAccount account = getOrCreateAccount(companyId);
        resetBalanceIfSubscriptionInactive(companyId, account);

        return CreditBalanceResponse.builder()
                .companyId(companyId)
                .balance(account.getBalance())
                .isUnlimited(false)
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
        deductCredit(companyId, reference, INITIAL_OPTIMIZATION_CREDIT_COST);
    }

    @Override
    @Transactional
    public void deductCredit(Long companyId, String reference, int amount) {


        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "Credit transaction reference must not be blank"
            );
        }

// Không trừ credit lần thứ hai cho cùng một Optimization Job.
        Optional<CreditTransaction> existingUsage =
                creditTransactionRepository.findByReferenceAndType(
                        reference,
                        CreditTransactionType.USAGE
                );

        if (existingUsage.isPresent()) {
            CreditTransaction existing = existingUsage.get();

            Long existingCompanyId = existing.getCreditAccount()
                    .getCompany()
                    .getId();

            if (!existingCompanyId.equals(companyId)) {
                throw new IllegalStateException(
                        "Credit reference belongs to another company"
                );
            }

            log.info(
                    "Credit already deducted for reference {}. Skipping duplicate.",
                    reference
            );
            return;
        }

        int creditsToDeduct = amount > 0 ? amount : INITIAL_OPTIMIZATION_CREDIT_COST;

        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> getOrCreateAccount(companyId));

        resetBalanceIfSubscriptionInactive(companyId, account);

        if (account.getBalance() < creditsToDeduct) {
            log.warn("Company {} has insufficient credits (balance: {}, required: {}) for reference {}",
                    companyId, account.getBalance(), creditsToDeduct, reference);
            throw new InsufficientCreditsException();
        }



        account.setBalance(account.getBalance() - creditsToDeduct);
        creditAccountRepository.save(account);

        CreditTransaction tx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(-creditsToDeduct)
                .type(CreditTransactionType.USAGE)
                .reference(reference)
                .refunded(false)
                .createdAt(LocalDateTime.now())
                .build();
        creditTransactionRepository.save(tx);
        log.info("Deducted {} credit(s) for company {}. New balance: {}", creditsToDeduct, companyId, account.getBalance());
    }


    @Override
    @Transactional
    public void refundCredit(Long companyId, String reference) {

        // 1. Kiểm tra reference.
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "Credit refund reference must not be blank"
            );
        }

        // 2. Tìm giao dịch trừ credit ban đầu.
        CreditTransaction originalUsage = creditTransactionRepository
                .findByReferenceAndType(
                        reference,
                        CreditTransactionType.USAGE
                )
                .orElse(null);

        if (originalUsage == null) {
            log.warn(
                    "No USAGE transaction found for reference {}. Skipping refund.",
                    reference
            );
            return;
        }

        // 3. Công ty nhận refund phải là công ty sở hữu giao dịch.
        Long originalCompanyId = originalUsage
                .getCreditAccount()
                .getCompany()
                .getId();

        if (companyId != null && !companyId.equals(originalCompanyId)) {
            throw new IllegalArgumentException(
                    "Refund company does not match original credit transaction"
            );
        }

        // 4. Khóa tài khoản trước khi kiểm tra và thay đổi số dư.
        CreditAccount account = creditAccountRepository
                .findByCompanyIdForUpdate(originalCompanyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Credit account not found for refund"
                ));

        // 5. Kiểm tra lại giao dịch sau khi có khóa tài khoản.
        originalUsage = creditTransactionRepository
                .findByReferenceAndType(
                        reference,
                        CreditTransactionType.USAGE
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Original credit transaction disappeared"
                ));

        if (Boolean.TRUE.equals(originalUsage.getRefunded())) {
            log.info(
                    "Reference {} already refunded. Skipping duplicate refund.",
                    reference
            );
            return;
        }

        // 6. Tính đúng số credit đã trừ ban đầu.
        int refundAmount = Math.abs(originalUsage.getAmount());

        if (refundAmount <= 0) {
            throw new IllegalStateException(
                    "Invalid original credit transaction amount"
            );
        }

        // 7. Hoàn credit.
        account.setBalance(account.getBalance() + refundAmount);
        creditAccountRepository.save(account);

        // 8. Đánh dấu giao dịch gốc đã hoàn.
        originalUsage.setRefunded(true);
        creditTransactionRepository.save(originalUsage);

        // 9. Ghi nhận giao dịch REFUND.
        CreditTransaction refundTx = CreditTransaction.builder()
                .creditAccount(account)
                .amount(refundAmount)
                .type(CreditTransactionType.REFUND)
                .reference(reference)
                .refunded(false)
                .createdAt(LocalDateTime.now())
                .build();

        creditTransactionRepository.save(refundTx);

        log.info(
                "Refunded {} credit(s) for company {} on reference {}. New balance: {}",
                refundAmount,
                originalCompanyId,
                reference,
                account.getBalance()
        );
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

    private void resetBalanceIfSubscriptionInactive(Long companyId, CreditAccount account) {
        boolean inactive = companySubscriptionRepository.findByCompanyId(companyId)
                .map(sub -> sub.getStatus() == SubscriptionStatus.EXPIRED || sub.getStatus() == SubscriptionStatus.CANCELLED)
                .orElse(false);
        if (inactive && account.getBalance() != null && account.getBalance() > 0) {
            log.info("Company {} subscription is EXPIRED/CANCELLED. Resetting credit balance from {} to 0.", companyId, account.getBalance());
            account.setBalance(0);
            creditAccountRepository.save(account);
        }
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
