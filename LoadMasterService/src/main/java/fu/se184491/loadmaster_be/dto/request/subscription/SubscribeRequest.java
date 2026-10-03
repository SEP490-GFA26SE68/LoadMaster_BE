package fu.se184491.loadmaster_be.dto.request.subscription;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscribeRequest {
    @NotNull(message = "planId không được để trống")
    private Long planId;

    @Builder.Default
    private PaymentGateway gateway = PaymentGateway.VNPAY;
}
