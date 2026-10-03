package fu.se184491.loadmaster_be.dto.request.subscription;

import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanRequest {

    @NotBlank(message = "Mã gói không được để trống")
    private String planCode;

    @NotBlank(message = "Tên gói không được để trống")
    private String name;

    @NotNull(message = "Giá không được để trống")
    @DecimalMin(value = "0.0", inclusive = true, message = "Giá không được nhỏ hơn 0")
    private BigDecimal priceVnd;

    @NotNull(message = "Tier không được để trống")
    private SubscriptionTier tier;

    @Positive(message = "Số credit hàng tháng phải lớn hơn 0")
    private Integer monthlyCredits;

    private AlgorithmTier algorithmTier;

    private String features;

    private BillingCycle billingCycle;

    private Integer maxVehicles;

    private Integer maxMonthlyJobs;

    @Builder.Default
    private Boolean active = true;
}
