package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionPlanService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingPlansToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SubscriptionPlanService plans = mock(SubscriptionPlanService.class);
    private final PricingPlansTool tool = new PricingPlansTool(plans);

    private static SubscriptionPlanResponse plan(long id, String name, SubscriptionTier tier, String price,
                                                 Integer credits) {
        return SubscriptionPlanResponse.builder()
                .id(id).planCode(tier + "_MONTHLY").name(name).tier(tier).priceVnd(new BigDecimal(price))
                .monthlyCredits(credits).algorithmTier(AlgorithmTier.EP_DBLF).features("[]")
                .billingCycle(BillingCycle.MONTHLY).active(true).activeCompanyCount(7).build();
    }

    @Test
    void plansAreCompactCheapestFirstAndUltimateIsUnlimited() {
        when(plans.getActivePlans()).thenReturn(List.of(
                plan(3, "Gói Toàn Diện (Ultimate AI)", SubscriptionTier.ULTIMATE, "7900000", 999),
                plan(1, "Gói Cơ Bản (Basic)", SubscriptionTier.BASIC, "1200000", 3000),
                plan(2, "Gói Chuyên Nghiệp (Pro)", SubscriptionTier.PRO, "3500000", 10000)));

        String json = ToolResults.json(tool.getPricingPlans());
        JsonNode out = JSON.readTree(json);

        assertThat(json).doesNotContain("activeCompanyCount").doesNotContain("planCode")
                .doesNotContain("\"id\"").doesNotContain("features");
        JsonNode rows = out.get("plans");
        assertThat(rows).extracting(row -> row.get("tier").asString()).containsExactly("BASIC", "PRO", "ULTIMATE");
        assertThat(rows.get(0).get("monthlyCredits").asInt()).isEqualTo(3000);
        assertThat(rows.get(0).get("priceVnd").decimalValue()).isEqualByComparingTo("1200000");
        assertThat(rows.get(2).get("monthlyCredits").isNull()).isTrue();
        assertThat(rows.get(2).get("unlimitedCredits").asBoolean()).isTrue();
        assertThat(rows.get(2).get("billingCycle").asString()).isEqualTo("MONTHLY");
    }

    @Test
    void creditCostsAndPricePerCreditComeFromTheCreditService() {
        when(plans.getActivePlans()).thenReturn(List.of());

        JsonNode out = JSON.readTree(ToolResults.json(tool.getPricingPlans()));

        assertThat(out.get("creditsPerOptimization").asInt()).isEqualTo(2);
        assertThat(out.get("creditsPerRerun").asInt()).isEqualTo(1);
        assertThat(out.get("minTopUpCredits").asInt()).isEqualTo(1);
        assertThat(out.get("pricePerCreditVnd").get("BASIC").decimalValue()).isEqualByComparingTo("400");
        assertThat(out.get("pricePerCreditVnd").get("PRO").decimalValue()).isEqualByComparingTo("326");
        assertThat(out.get("pricePerCreditVnd").get("ULTIMATE").decimalValue()).isEqualByComparingTo("263");
    }
}
