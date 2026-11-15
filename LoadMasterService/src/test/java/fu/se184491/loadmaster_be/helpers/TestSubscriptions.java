package fu.se184491.loadmaster_be.helpers;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gói cước đang hiệu lực cho công ty của test tích hợp. {@code FlaggedUserEnforcementFilter} chặn mọi API nghiệp vụ của
 * công ty không có gói hoặc gói đã hết hạn, nên test gọi API bằng người của một công ty phải cho công ty đó một gói.
 */
public final class TestSubscriptions {

    private TestSubscriptions() {
    }

    /** Lưu một gói Basic đang bán và gói của công ty còn hạn 30 ngày. */
    public static CompanySubscription activate(EntityManager entityManager, Company company) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .planCode("TEST-ACTIVE-" + company.getCompanyCode())
                .name("Basic")
                .price(new BigDecimal("1200000"))
                .billingCycle(BillingCycle.MONTHLY)
                .tier(SubscriptionTier.BASIC)
                .monthlyCredits(3000)
                .active(true)
                .build();
        entityManager.persist(plan);
        LocalDateTime now = LocalDateTime.now();
        CompanySubscription subscription = CompanySubscription.builder()
                .company(company)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(now.minusDays(1))
                .expiresAt(now.plusDays(30))
                .autoRenew(true)
                .build();
        entityManager.persist(subscription);
        return subscription;
    }
}
