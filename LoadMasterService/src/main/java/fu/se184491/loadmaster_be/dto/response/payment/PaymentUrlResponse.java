package fu.se184491.loadmaster_be.dto.response.payment;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentUrlResponse {
    private String paymentUrl;
    private String transactionId;
    private Long id;
    private String status;
}
