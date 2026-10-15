package fu.se184491.loadmaster_be.dto.response.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceResponse {
    private Long id;
    private String invoiceNumber;
    private Long companyId;
    private String companyName;
    private Long companySubscriptionId;
    private String planName;
    private BigDecimal amount;
    private InvoiceStatus status;
    private String billingPeriod;
    private Instant createdAt;
    private LocalDateTime paidAt;
}
