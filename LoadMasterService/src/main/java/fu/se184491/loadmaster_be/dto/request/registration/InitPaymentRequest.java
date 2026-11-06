package fu.se184491.loadmaster_be.dto.request.registration;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitPaymentRequest {

    @NotNull(message = "Token phiên đăng ký không được để trống")
    private String token;

    @NotNull(message = "Vui lòng chọn gói dịch vụ")
    private Long planId;

    @NotNull(message = "Vui lòng chọn chu kỳ thanh toán")
    private BillingCycle billingCycle;
}
