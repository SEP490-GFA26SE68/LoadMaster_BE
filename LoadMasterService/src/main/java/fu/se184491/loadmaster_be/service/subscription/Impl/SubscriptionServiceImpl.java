package fu.se184491.loadmaster_be.service.subscription.Impl;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionRenewalResponse;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionServiceImpl implements SubscriptionService {

    private final CompanyRepository companyRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CreditService creditService;
    private final PaymentGatewayService paymentGatewayService;
    private final PaymentTransactionRepository paymentTransactionRepository;

    /** Gói còn hạn chỉ gia hạn được trong chừng này ngày cuối kỳ; gói đã hết hạn thì lúc nào cũng được. */
    public static final int RENEWAL_WINDOW_DAYS = 7;
    static final String RENEWAL_ORDER = "SUBSCRIPTION_RENEWAL";
    private static final List<String> PLAN_ORDERS = List.of("SUBSCRIPTION", "SUBSCRIPTION_YEARLY", RENEWAL_ORDER);

    @Override
    @Transactional
    public SubscriptionCurrentResponse getCurrentSubscription(Long companyId) {
        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        CreditBalanceResponse balanceRes = creditService.getBalance(companyId);
        int balance = balanceRes != null ? balanceRes.getBalance() : 0;

        if (subOpt.isEmpty()) {
            return SubscriptionCurrentResponse.builder()
                    .status(null)
                    .creditsBalance(balance)
                    .build();
        }

        CompanySubscription sub = subOpt.get();
        SubscriptionPlan plan = sub.getPlan();

        return SubscriptionCurrentResponse.builder()
                .subscriptionId(sub.getId())
                .planId(plan != null ? plan.getId() : null)
                .planName(plan != null ? plan.getName() : null)
                .tier(plan != null ? plan.getTier() : null)
                .status(sub.getStatus())
                .startedAt(sub.getStartedAt())
                .expiresAt(sub.getExpiresAt())
                .autoRenew(sub.getAutoRenew())
                .creditsBalance(balance)
                .creditsMonthlyGrant(plan != null ? plan.getMonthlyCredits() : 0)
                .algorithmTier(plan != null ? plan.getAlgorithmTier() : null)
                .build();
    }

    @Override
    @Transactional
    public PaymentUrlResponse subscribe(Long companyId, SubscribeRequest request, String ipAddress) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        Optional<CompanySubscription> existingSubOpt = companySubscriptionRepository
                .findByCompanyId(companyId);
        if (existingSubOpt.isEmpty()) {
            existingSubOpt = companySubscriptionRepository
                    .findByCompanyIdAndStatus(companyId, SubscriptionStatus.ACTIVE);
        }

        if (existingSubOpt.isPresent()) {
            CompanySubscription existingSub = existingSubOpt.get();
            if (existingSub.getStatus() == SubscriptionStatus.CANCELLED) {
                throw new AppException(
                        ErrorCode.ACTIVE_SUBSCRIPTION_ALREADY_EXISTS,
                        "Hợp đồng gói cước đang bị tạm ngưng/hủy. Vui lòng gửi phiếu hỗ trợ (Support Ticket) để ký lại hợp đồng."
                );
            }
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        if (Boolean.FALSE.equals(plan.getActive())) {
            throw new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND);
        }

        boolean isExtendingSameActivePlan = false;
        int remainingMonthsToYearCap = 12;

        if (existingSubOpt.isPresent()) {
            CompanySubscription existingSub = existingSubOpt.get();
            boolean isSamePlan = existingSub.getPlan() != null
                    && existingSub.getPlan().getId() != null
                    && existingSub.getPlan().getId().equals(plan.getId());

            if (existingSub.getStatus() == SubscriptionStatus.ACTIVE && !isSamePlan) {
                int currentRank = tierRank(existingSub.getPlan() != null ? existingSub.getPlan().getTier() : null);
                int targetRank = tierRank(plan.getTier());
                if (targetRank <= currentRank) {
                    throw new AppException(
                            ErrorCode.ACTIVE_SUBSCRIPTION_ALREADY_EXISTS,
                            "Khi gói hiện tại đang hoạt động, doanh nghiệp chỉ có thể tự gia hạn gói đang dùng hoặc nâng cấp lên gói cao hơn."
                    );
                }
            } else if (existingSub.getStatus() == SubscriptionStatus.ACTIVE && isSamePlan) {
                isExtendingSameActivePlan = true;
                int purchasedMonths = calculatePurchasedMonths(existingSub);
                remainingMonthsToYearCap = 12 - purchasedMonths;
                if (remainingMonthsToYearCap <= 0) {
                    throw new AppException(
                            ErrorCode.ACTIVE_SUBSCRIPTION_ALREADY_EXISTS,
                            "Gói hiện tại đã đạt tối đa 12 tháng kể từ lần kích hoạt đầu tiên. Vui lòng đợi hết hạn để gia hạn tiếp."
                    );
                }
            }
        }

        BigDecimal basePrice = plan.getPriceVnd() != null ? plan.getPriceVnd() : BigDecimal.ZERO;
        BillingCycle requestedCycle = request.getBillingCycle();
        boolean isYearlyRequest = requestedCycle == BillingCycle.YEARLY;
        int monthsToCharge = isYearlyRequest
                ? (isExtendingSameActivePlan ? remainingMonthsToYearCap : 12)
                : 1;
        BigDecimal price = (isYearlyRequest && plan.getBillingCycle() != BillingCycle.YEARLY)
                ? basePrice.multiply(BigDecimal.valueOf(monthsToCharge))
                : basePrice;

        // If plan is free, activate immediately
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            activateSubscription(companyId, plan.getId(), requestedCycle);
            return PaymentUrlResponse.builder()
                    .status("ACTIVE")
                    .paymentUrl(null)
                    .transactionId(null)
                    .build();
        }

        // Paid plan -> Trigger Payment Gateway
        PaymentGateway gateway = request.getGateway() != null ? request.getGateway() : PaymentGateway.VNPAY;
        String orderType = isYearlyRequest ? "SUBSCRIPTION_YEARLY" : "SUBSCRIPTION";
        String orderInfo = "Đăng ký gói " + plan.getName();
        return paymentGatewayService.createPayment(company, gateway, orderType, plan.getId(), price, orderInfo, ipAddress);
    }

    private int tierRank(SubscriptionTier tier) {
        if (tier == null) return 0;
        return switch (tier) {
            case BASIC -> 1;
            case PRO -> 2;
            case ULTIMATE -> 3;
        };
    }

    @Override
    @Transactional
    public void activateSubscription(Long companyId, Long planId) {
        activateSubscription(companyId, planId, null);
    }

    @Override
    @Transactional
    public void activateSubscription(Long companyId, Long planId, BillingCycle requestedCycle) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        CompanySubscription sub = companySubscriptionRepository.findByCompanyId(companyId)
                .orElseGet(() -> CompanySubscription.builder()
                        .company(company)
                        .build());

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        boolean isExtendingSameActivePlan = sub.getStatus() == SubscriptionStatus.ACTIVE
                && sub.getPlan() != null
                && sub.getPlan().getId() != null
                && sub.getPlan().getId().equals(plan.getId())
                && sub.getExpiresAt() != null
                && sub.getExpiresAt().isAfter(now);

        if (!isExtendingSameActivePlan && sub.getId() != null) {
            creditService.resetBalance(companyId);
        }

        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        if (!isExtendingSameActivePlan || sub.getStartedAt() == null) {
            sub.setStartedAt(now);
        }
        if (isExtendingSameActivePlan && requestedCycle == BillingCycle.YEARLY && sub.getStartedAt() != null) {
            sub.setExpiresAt(sub.getStartedAt().truncatedTo(ChronoUnit.SECONDS).plusMonths(12));
        } else {
            LocalDateTime baseExpiry = isExtendingSameActivePlan
                    ? sub.getExpiresAt().truncatedTo(ChronoUnit.SECONDS)
                    : now;
            sub.setExpiresAt(calculateExpiresAt(baseExpiry, plan, requestedCycle).truncatedTo(ChronoUnit.SECONDS));
        }
        sub.setAutoRenew(true);
        sub.normalizeTimestamps();

        companySubscriptionRepository.save(sub);
        log.info("Activated subscription id={} for company={} plan={}", sub.getId(), companyId, plan.getName());

        // Grant monthly credits only when starting a new cycle or switching plans (not when extending active plan early)
        if (!isExtendingSameActivePlan) {
            int monthlyCredits = plan.getMonthlyCredits() != null ? plan.getMonthlyCredits() : 0;
            if (monthlyCredits > 0) {
                creditService.grantMonthlyCredits(companyId, monthlyCredits);
            }
        }
    }

    private int calculatePurchasedMonths(CompanySubscription sub) {
        if (sub == null || sub.getStartedAt() == null || sub.getExpiresAt() == null) {
            return 1;
        }
        long months = ChronoUnit.MONTHS.between(sub.getStartedAt(), sub.getExpiresAt());
        if (months > 0) {
            return (int) months;
        }
        long days = ChronoUnit.DAYS.between(sub.getStartedAt(), sub.getExpiresAt());
        return Math.max(1, (int) Math.round(days / 30.0));
    }

    @Override
    @Transactional
    public void cancelSubscription(Long companyId) {
        CompanySubscription sub = companySubscriptionRepository
                .findByCompanyIdAndStatus(companyId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.NO_ACTIVE_SUBSCRIPTION));

        sub.setAutoRenew(false);
        companySubscriptionRepository.save(sub);
        log.info("Cancelled auto-renewal for company subscription id={}", sub.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionRenewalResponse getRenewal(Long companyId) {
        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        SubscriptionRenewalResponse.SubscriptionRenewalResponseBuilder out = SubscriptionRenewalResponse.builder()
                .renewalWindowDays(RENEWAL_WINDOW_DAYS)
                .pendingPayment(pendingPlanPayment(companyId));
        if (subOpt.isEmpty() || subOpt.get().getPlan() == null) {
            return out.renewable(false).reason("NO_SUBSCRIPTION").build();
        }
        CompanySubscription sub = subOpt.get();
        SubscriptionPlan plan = sub.getPlan();
        String reason = renewalBlock(sub, now);
        return out.planId(plan.getId())
                .planName(plan.getName())
                .tier(plan.getTier())
                .planActive(!Boolean.FALSE.equals(plan.getActive()))
                .status(sub.getStatus())
                .expiresAt(sub.getExpiresAt())
                .amountVnd(plan.getPriceVnd() != null ? plan.getPriceVnd() : BigDecimal.ZERO)
                .renewable(reason == null)
                .reason(reason)
                .build();
    }

    @Override
    @Transactional
    public PaymentUrlResponse renew(Long companyId, PaymentGateway gateway, String ipAddress) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        CompanySubscription sub = companySubscriptionRepository.findByCompanyId(companyId)
                .filter(found -> found.getPlan() != null)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (renewalBlock(sub, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)) != null) {
            throw new AppException(ErrorCode.SUBSCRIPTION_NOT_RENEWABLE);
        }
        // Gói ngừng bán vẫn gia hạn được cho công ty đang dùng (đăng ký mới thì không).
        SubscriptionPlan plan = sub.getPlan();
        BigDecimal price = plan.getPriceVnd() != null ? plan.getPriceVnd() : BigDecimal.ZERO;
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            activateSubscription(companyId, plan.getId());
            return PaymentUrlResponse.builder().status("ACTIVE").build();
        }
        PaymentGateway chosen = gateway != null ? gateway : PaymentGateway.VNPAY;
        Optional<PaymentTransaction> pending = paymentTransactionRepository
                .findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(companyId, PaymentStatus.PENDING, List.of(RENEWAL_ORDER))
                .filter(txn -> chosen == PaymentGateway.MOCK && txn.getGateway() == PaymentGateway.MOCK
                        && plan.getId().equals(txn.getTargetId())
                        && txn.getAmountVnd() != null && txn.getAmountVnd().compareTo(price) == 0);
        if (pending.isPresent()) {
            PaymentTransaction txn = pending.get();
            return PaymentUrlResponse.builder()
                    .id(txn.getId())
                    .transactionId(txn.getGatewayTransactionId())
                    .paymentUrl(mockCheckoutUrl(txn))
                    .status(txn.getStatus().name())
                    .amountVnd(txn.getAmountVnd())
                    .build();
        }
        return paymentGatewayService.createPayment(company, chosen, RENEWAL_ORDER, plan.getId(), price,
                "Gia hạn gói " + plan.getName(), ipAddress);
    }

    /** Lý do không gia hạn được, {@code null} khi được: gói hết hạn, hoặc còn hạn trong {@link #RENEWAL_WINDOW_DAYS} ngày cuối. */
    private static String renewalBlock(CompanySubscription sub, LocalDateTime now) {
        SubscriptionStatus status = sub.getStatus();
        if (status == SubscriptionStatus.CANCELLED) return "CANCELLED";
        if (status == SubscriptionStatus.TRIAL) return "TRIAL";
        if (status == SubscriptionStatus.EXPIRED) return null;
        LocalDateTime expiresAt = sub.getExpiresAt();
        if (expiresAt == null || expiresAt.isAfter(now.plusDays(RENEWAL_WINDOW_DAYS))) return "NOT_DUE";
        return null;
    }

    private SubscriptionRenewalResponse.PendingPayment pendingPlanPayment(Long companyId) {
        return paymentTransactionRepository
                .findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(companyId, PaymentStatus.PENDING, PLAN_ORDERS)
                .map(txn -> SubscriptionRenewalResponse.PendingPayment.builder()
                        .id(txn.getId())
                        .transactionId(txn.getGatewayTransactionId())
                        .paymentUrl(mockCheckoutUrl(txn))
                        .orderType(txn.getOrderType())
                        .planId(txn.getTargetId())
                        .amountVnd(txn.getAmountVnd())
                        .createdAt(txn.getCreatedAt())
                        .build())
                .orElse(null);
    }

    /** Liên kết của cổng giả lập; cổng thật không lưu liên kết nên {@code null}. */
    private static String mockCheckoutUrl(PaymentTransaction txn) {
        return txn.getGateway() == PaymentGateway.MOCK
                ? "/api/payment/mock-checkout?txnRef=" + txn.getGatewayTransactionId()
                : null;
    }

    @Override
    @Transactional
    public void autoRenewExpiringSubscriptions() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<CompanySubscription> expiredSubs = companySubscriptionRepository
                .findByStatusAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now);

        for (CompanySubscription sub : expiredSubs) {
            if (Boolean.TRUE.equals(sub.getAutoRenew())) {
                // If plan is free or mock auto renew:
                SubscriptionPlan plan = sub.getPlan();
                if (plan != null && (plan.getPriceVnd() == null || plan.getPriceVnd().compareTo(BigDecimal.ZERO) == 0)) {
                    // Mã theo kỳ vừa hết: job chạy lại cũng không cấp hai lần cho cùng một kỳ.
                    String renewRef = "RENEW-" + sub.getId() + "-" + sub.getExpiresAt();
                    sub.setStartedAt(now);
                    sub.setExpiresAt(calculateExpiresAt(now, plan).truncatedTo(ChronoUnit.SECONDS));
                    sub.normalizeTimestamps();
                    companySubscriptionRepository.save(sub);
                    if (plan.getMonthlyCredits() != null && plan.getMonthlyCredits() > 0) {
                        creditService.grantMonthlyCredits(sub.getCompany().getId(), plan.getMonthlyCredits(), renewRef);
                    }
                    log.info("Auto-renewed free subscription for company={}", sub.getCompany().getId());
                } else {
                    // For paid plans, mark expired until payment is made
                    sub.setStatus(SubscriptionStatus.EXPIRED);
                    companySubscriptionRepository.save(sub);
                    log.info("Subscription expired for company={}. Awaiting manual/scheduled renewal payment.", sub.getCompany().getId());
                }
            } else {
                sub.setStatus(SubscriptionStatus.EXPIRED);
                companySubscriptionRepository.save(sub);
                log.info("Subscription marked EXPIRED for company={}", sub.getCompany().getId());
            }
        }
    }

    private LocalDateTime calculateExpiresAt(LocalDateTime start, SubscriptionPlan plan) {
        return calculateExpiresAt(start, plan, null);
    }

    private LocalDateTime calculateExpiresAt(LocalDateTime start, SubscriptionPlan plan, BillingCycle requestedCycle) {
        if (requestedCycle == BillingCycle.YEARLY || (requestedCycle == null && plan.getBillingCycle() == BillingCycle.YEARLY)) {
            return start.plusYears(1);
        }
        return start.plusMonths(1);
    }
}
