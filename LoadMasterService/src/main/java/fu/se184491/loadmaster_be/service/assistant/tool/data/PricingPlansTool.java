package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gói cước đang bán và giá credit — thông tin công khai, nên cả khách cũng dùng được. Dựng bản gọn từ danh mục gói:
 * không mã gói, không số công ty đang dùng. Ultimate không giới hạn credit (theo hạng, như {@code CreditServiceImpl}).
 */
@Component
@RequiredArgsConstructor
public class PricingPlansTool implements AssistantTool {

    private final SubscriptionPlanService plans;

    /** {@code monthlyCredits} là {@code null} khi không giới hạn. */
    public record Plan(String name, SubscriptionTier tier, BigDecimal priceVnd, Integer monthlyCredits,
                       boolean unlimitedCredits, BillingCycle billingCycle) {
    }

    /**
     * Credit một lần tối ưu, giá một credit khi nạp thêm theo hạng gói, số nạp ít nhất. Không có số riêng cho chạy lại:
     * "Chạy lại giữ ghim" của web và app gửi lại {@code optimize-loading} kèm {@code keepPinsFromPlanId} — một lần chạy
     * ba phương án, trừ đúng {@code creditsPerOptimization}. Đường chạy lại một phương án
     * ({@code POST /api/load-plans/{id}/rerun}) không có lối vào trên màn nào nên số của nó không đưa cho mô hình.
     */
    public record Pricing(List<Plan> plans, int creditsPerOptimization,
                          Map<SubscriptionTier, BigDecimal> pricePerCreditVnd, int minTopUpCredits) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.allOf(UserRole.class);
    }

    @Override
    public boolean guest() {
        return true;
    }

    @Tool(name = "get_pricing_plans", description = """
            Các gói cước LoadMaster đang bán: tên, hạng, giá (VND), credit mỗi tháng (null là không giới hạn), chu \
            kỳ thanh toán; số credit của một lần tối ưu xếp hàng (một lần chạy ra ba phương án A, B, C; "Chạy lại \
            giữ ghim" cũng là một lần tối ưu như vậy và tốn đúng số credit này); giá một credit khi nạp thêm theo \
            hạng gói. Dùng khi hỏi về giá, gói, credit hay chọn gói.""")
    public Pricing getPricingPlans() {
        List<Plan> rows = plans.getActivePlans().stream()
                .sorted(Comparator.comparing(SubscriptionPlanResponse::getPriceVnd,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PricingPlansTool::planOf)
                .toList();
        Map<SubscriptionTier, BigDecimal> perCredit = new EnumMap<>(SubscriptionTier.class);
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            perCredit.put(tier, CreditServiceImpl.pricePerCredit(tier));
        }
        return new Pricing(rows, CreditServiceImpl.INITIAL_OPTIMIZATION_CREDIT_COST, perCredit,
                CreditServiceImpl.MIN_TOPUP_CREDITS);
    }

    private static Plan planOf(SubscriptionPlanResponse plan) {
        boolean unlimited = plan.getTier() == SubscriptionTier.ULTIMATE;
        return new Plan(plan.getName(), plan.getTier(), plan.getPriceVnd(),
                unlimited ? null : plan.getMonthlyCredits(), unlimited, plan.getBillingCycle());
    }
}
