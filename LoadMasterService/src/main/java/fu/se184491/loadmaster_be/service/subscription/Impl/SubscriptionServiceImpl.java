package fu.se184491.loadmaster_be.service.subscription.Impl;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
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

    @Override
    @Transactional(readOnly = true)
    public SubscriptionCurrentResponse getCurrentSubscription(Long companyId) {
        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        CreditBalanceResponse balanceRes = creditService.getBalance(companyId);
        int balance = balanceRes != null ? balanceRes.getBalance() : 0;

        if (subOpt.isEmpty() || subOpt.get().getStatus() != SubscriptionStatus.ACTIVE) {
            return SubscriptionCurrentResponse.builder()
                    .status(subOpt.map(CompanySubscription::getStatus).orElse(null))
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

        Optional<CompanySubscription> activeOpt = companySubscriptionRepository
                .findByCompanyIdAndStatus(companyId, SubscriptionStatus.ACTIVE);

        if (activeOpt.isPresent()) {
            CompanySubscription activeSub = activeOpt.get();
            if (activeSub.getExpiresAt() != null && activeSub.getExpiresAt().isAfter(LocalDateTime.now())) {
                throw new AppException(ErrorCode.ACTIVE_SUBSCRIPTION_ALREADY_EXISTS);
            }
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        if (Boolean.FALSE.equals(plan.getActive())) {
            throw new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND);
        }

        BigDecimal price = plan.getPriceVnd() != null ? plan.getPriceVnd() : BigDecimal.ZERO;

        // If plan is free, activate immediately
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            activateSubscription(companyId, plan.getId());
            return PaymentUrlResponse.builder()
                    .status("ACTIVE")
                    .paymentUrl(null)
                    .transactionId(null)
                    .build();
        }

        // Paid plan -> Trigger Payment Gateway
        PaymentGateway gateway = request.getGateway() != null ? request.getGateway() : PaymentGateway.VNPAY;
        String orderInfo = "Đăng ký gói " + plan.getName();
        return paymentGatewayService.createPayment(company, gateway, "SUBSCRIPTION", plan.getId(), price, orderInfo, ipAddress);
    }

    @Override
    @Transactional
    public void activateSubscription(Long companyId, Long planId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        CompanySubscription sub = companySubscriptionRepository.findByCompanyId(companyId)
                .orElseGet(() -> CompanySubscription.builder()
                        .company(company)
                        .build());

        LocalDateTime now = LocalDateTime.now();
        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartedAt(now);
        sub.setExpiresAt(now.plusMonths(1));
        sub.setAutoRenew(true);

        companySubscriptionRepository.save(sub);
        log.info("Activated subscription id={} for company={} plan={}", sub.getId(), companyId, plan.getName());

        // Grant monthly credits
        int monthlyCredits = plan.getMonthlyCredits() != null ? plan.getMonthlyCredits() : 0;
        if (monthlyCredits > 0) {
            creditService.grantMonthlyCredits(companyId, monthlyCredits);
        }
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
    @Transactional
    public void autoRenewExpiringSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        List<CompanySubscription> expiredSubs = companySubscriptionRepository
                .findByStatusAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now);

        for (CompanySubscription sub : expiredSubs) {
            if (Boolean.TRUE.equals(sub.getAutoRenew())) {
                // If plan is free or mock auto renew:
                SubscriptionPlan plan = sub.getPlan();
                if (plan != null && (plan.getPriceVnd() == null || plan.getPriceVnd().compareTo(BigDecimal.ZERO) == 0)) {
                    sub.setStartedAt(now);
                    sub.setExpiresAt(now.plusMonths(1));
                    companySubscriptionRepository.save(sub);
                    if (plan.getMonthlyCredits() != null && plan.getMonthlyCredits() > 0) {
                        creditService.grantMonthlyCredits(sub.getCompany().getId(), plan.getMonthlyCredits());
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
}
