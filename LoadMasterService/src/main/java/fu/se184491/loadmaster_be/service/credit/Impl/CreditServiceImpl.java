package fu.se184491.loadmaster_be.service.credit.Impl;

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
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditTransactionRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditServiceImpl implements CreditService {

    /** Mỗi lần tối ưu 3D (ba phương án ứng viên) xài 2 credit; chạy lại tốn 1 credit. */
    public static final int INITIAL_OPTIMIZATION_CREDIT_COST = 2;
    public static final int RERUN_OPTIMIZATION_CREDIT_COST = 1;
    /** Số dư sau một lần trừ xuống tới mức này thì báo "Sắp hết credit" cho quản trị công ty (đề xuất, chờ chốt). */
    public static final int LOW_CREDIT_THRESHOLD = 10;

    /** Đơn giá mua lẻ credit (VND/credit) theo hạng gói hiện tại của công ty. */
    public static final BigDecimal PRICE_PER_CREDIT_BASIC_VND = new BigDecimal("400");
    public static final BigDecimal PRICE_PER_CREDIT_PRO_VND = new BigDecimal("326");
    public static final BigDecimal PRICE_PER_CREDIT_ULTIMATE_VND = new BigDecimal("263");

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CompanyRepository companyRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentGatewayService paymentGatewayService;

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
        return creditAccountRepository.findByCompanyId(companyId)
                .map(account -> creditTransactionRepository.findByCreditAccountIdOrderByCreatedAtDesc(account.getId(), pageable)
                        .map(this::toResponse))
                .orElseGet(() -> Page.empty(pageable));
    }

    @Override
    @Transactional
    public PaymentUrlResponse topup(Long companyId, CreditTopupRequest request, String clientIp) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        if (subOpt.isPresent() && subOpt.get().getStatus() != SubscriptionStatus.ACTIVE) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "Gói cước của công ty không ở trạng thái hoạt động. Vui lòng gia hạn gói trước khi mua thêm credit."
            );
        }

        SubscriptionTier tier = (subOpt.isPresent()
                && subOpt.get().getPlan() != null
                && subOpt.get().getPlan().getTier() != null)
                ? subOpt.get().getPlan().getTier()
                : SubscriptionTier.BASIC;

        int requestedCredits = request.getCredits();

        BigDecimal pricePerCredit;
        if (tier == SubscriptionTier.BASIC) {
            pricePerCredit = PRICE_PER_CREDIT_BASIC_VND;
        } else if (tier == SubscriptionTier.PRO) {
            pricePerCredit = PRICE_PER_CREDIT_PRO_VND;
        } else {
            pricePerCredit = PRICE_PER_CREDIT_ULTIMATE_VND;
        }

        BigDecimal totalAmount = pricePerCredit.multiply(BigDecimal.valueOf(requestedCredits));
        PaymentGateway gateway = request.getGateway() != null ? request.getGateway() : PaymentGateway.VNPAY;

        return paymentGatewayService.createPayment(
                company,
                gateway,
                "CREDIT_TOPUP",
                Long.valueOf(requestedCredits),
                totalAmount,
                "Nạp " + requestedCredits + " credits",
                clientIp
        );
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
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();
        tx.prePersist();
        creditTransactionRepository.save(tx);
        log.info("Deducted {} credit(s) for company {}. New balance: {}", creditsToDeduct, companyId, account.getBalance());
        auditLowBalance(companyId, account.getBalance());
    }

    /** Sự kiện của hệ thống (không người làm), thuộc công ty: nguồn của thông báo "Sắp hết credit". */
    private void auditLowBalance(Long companyId, Integer balance) {
        if (balance == null || balance > LOW_CREDIT_THRESHOLD || isUnlimitedPlan(companyId)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("balance", balance);
        values.put("threshold", LOW_CREDIT_THRESHOLD);
        auditLogRepository.save(AuditLog.builder()
                .companyId(companyId)
                .actionType("CREDIT_LOW_BALANCE")
                .entityName("Company")
                .entityId(String.valueOf(companyId))
                .newValues(values)
                .createdAt(Instant.now())
                .build());
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

        if (Boolean.TRUE.equals(originalUsage.getRefunded())) {
            log.info(
                    "Reference {} already refunded. Skipping duplicate refund.",
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
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();
        refundTx.prePersist();

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
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();
        tx.prePersist();
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
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .build();
        tx.prePersist();
        creditTransactionRepository.save(tx);
        log.info("Purchased/Added {} credits to company {}. New balance: {}", amount, companyId, account.getBalance());
    }

    @Override
    @Transactional
    public void resetBalance(Long companyId) {
        CreditAccount account = creditAccountRepository.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> getOrCreateAccount(companyId));
        if (account.getBalance() != null && account.getBalance() > 0) {
            log.info("Resetting credit balance for company {} from {} to 0 upon plan change/activation.", companyId, account.getBalance());
            account.setBalance(0);
            creditAccountRepository.save(account);
        }
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
        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        if (subOpt.isEmpty()) {
            return;
        }
        CompanySubscription sub = subOpt.get();
        boolean inactive = sub.getStatus() == SubscriptionStatus.EXPIRED || sub.getStatus() == SubscriptionStatus.CANCELLED;
        if (inactive && account.getBalance() != null && account.getBalance() > 0) {
            log.info("Company {} subscription is EXPIRED/CANCELLED. Resetting credit balance from {} to 0.", companyId, account.getBalance());
            account.setBalance(0);
            creditAccountRepository.save(account);
            return;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (sub.getStatus() == SubscriptionStatus.ACTIVE
                && sub.getId() != null
                && sub.getStartedAt() != null
                && sub.getExpiresAt() != null
                && now.isBefore(sub.getExpiresAt())
                && sub.getPlan() != null
                && sub.getPlan().getMonthlyCredits() != null
                && sub.getPlan().getMonthlyCredits() > 0) {
            long completedMonths = ChronoUnit.MONTHS.between(sub.getStartedAt(), now);
            if (completedMonths >= 1) {
                long cycleMonth = completedMonths + 1;
                String cycleRef = "CYCLE-" + sub.getId() + "-M" + cycleMonth;
                if (creditTransactionRepository.findByReferenceAndType(cycleRef, CreditTransactionType.MONTHLY_GRANT).isEmpty()) {
                    int baseMonthlyCredits = sub.getPlan().getMonthlyCredits();
                    account.setBalance(baseMonthlyCredits);
                    creditAccountRepository.save(account);

                    CreditTransaction tx = CreditTransaction.builder()
                            .creditAccount(account)
                            .amount(baseMonthlyCredits)
                            .type(CreditTransactionType.MONTHLY_GRANT)
                            .reference(cycleRef)
                            .refunded(false)
                            .createdAt(now)
                            .build();
                    tx.prePersist();
                    creditTransactionRepository.save(tx);
                    log.info("Reset credit balance for company {} entering billing month {} back to plan base credits {}.",
                            companyId, cycleMonth, baseMonthlyCredits);
                }
            }
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
