package fu.se184491.loadmaster_be.dto.response.credit;

import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditTransactionResponse {
    private Long id;
    private Integer amount;
    private CreditTransactionType type;
    private String reference;
    private Boolean refunded;
    private LocalDateTime createdAt;
}
