package fu.se184491.loadmaster_be.dto.request.subscription;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RenewSubscriptionRequest {
    @Builder.Default
    private PaymentGateway gateway = PaymentGateway.VNPAY;
}
