package fu.se184491.loadmaster_be.dto.response.registration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentLinkResponse {

    private String paymentUrl;
    private String paymentRef;
    private BigDecimal amountVnd;
    private LocalDateTime expiresAt;
}
