package fu.se184491.loadmaster_be.config;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionPlanInitializer implements ApplicationRunner {

    private final SubscriptionPlanRepository subscriptionPlanRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (!subscriptionPlanRepository.existsByPlanCode("BASIC_MONTHLY")) {
            SubscriptionPlan basicPlan = SubscriptionPlan.builder()
                    .planCode("BASIC_MONTHLY")
                    .name("Gói Cơ Bản (Basic)")
                    .price(new BigDecimal("1200000"))
                    .billingCycle(BillingCycle.MONTHLY)
                    .tier(SubscriptionTier.BASIC)
                    .monthlyCredits(3000)
                    .algorithmTier(AlgorithmTier.EP_DBLF)
                    .features("[\"Tối ưu xếp hàng 3D cơ bản (EP-DBLF)\",\"3.000 Credits / tháng (2 Credits/lần đầu, 1 Credit/lần chạy lại)\",\"Quản lý đội xe & điều phối chuyến\",\"Hỗ trợ kỹ thuật tiêu chuẩn\"]")
                    .active(true)
                    .build();
            subscriptionPlanRepository.save(basicPlan);
        }

        if (!subscriptionPlanRepository.existsByPlanCode("PRO_MONTHLY")) {
            SubscriptionPlan proPlan = SubscriptionPlan.builder()
                    .planCode("PRO_MONTHLY")
                    .name("Gói Chuyên Nghiệp (Pro)")
                    .price(new BigDecimal("4900000"))
                    .billingCycle(BillingCycle.MONTHLY)
                    .tier(SubscriptionTier.PRO)
                    .monthlyCredits(10000)
                    .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                    .features("[\"Tối ưu 3D nâng cao (EP-DBLF + Genetic Algorithm)\",\"15.000 Credits / tháng (2 Credits/lần đầu, 1 Credit/lần chạy lại)\",\"Tối ưu lộ trình đa điểm dừng (LIFO)\",\"Hỗ trợ kỹ thuật ưu tiên 24/7\"]")
                    .active(true)
                    .build();
            subscriptionPlanRepository.save(proPlan);
        }

        if (!subscriptionPlanRepository.existsByPlanCode("ULTIMATE_MONTHLY")) {
            SubscriptionPlan ultimatePlan = SubscriptionPlan.builder()
                    .planCode("ULTIMATE_MONTHLY")
                    .name("Gói Toàn Diện (Ultimate AI)")
                    .price(new BigDecimal("7900000"))
                    .billingCycle(BillingCycle.MONTHLY)
                    .tier(SubscriptionTier.ULTIMATE)
                    .monthlyCredits(30000)
                    .algorithmTier(AlgorithmTier.EP_DBLF_GA_AI)
                    .features("[\"Toàn bộ 3 thuật toán + AI Heuristic (EP-DBLF + GA + AI)\",\"30.000 Credits / tháng (Đủ vận hành 500 xe/ngày suốt 30 ngày)\",\"Ghim kiện hàng & Rerun tinh chỉnh chỉ 1 Credit\",\"Cam kết SLA 99.5% & Chuyên gia hỗ trợ riêng\"]")
                    .active(true)
                    .build();
            subscriptionPlanRepository.save(ultimatePlan);
        }

        log.info("Ensured 3 default SubscriptionPlans (BASIC: 3,000 cr, PRO: 15,000 cr, ULTIMATE: 30,000 cr)");
    }
}
