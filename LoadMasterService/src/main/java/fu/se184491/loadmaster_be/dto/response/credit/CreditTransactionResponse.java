package fu.se184491.loadmaster_be.dto.response.credit;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
