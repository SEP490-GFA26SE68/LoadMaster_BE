package fu.se184491.loadmaster_be.dto.request.credit;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditTopupRequest {
    @NotNull(message = "credits không được để trống")
    @Min(value = 1, message = "Số credit tối thiểu là 1")
    private Integer credits;

    @Builder.Default
    private PaymentGateway gateway = PaymentGateway.VNPAY;
}
