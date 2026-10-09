package fu.se184491.loadmaster_be.dto.response.order;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private Long id;
    private Long companyId;
    private String orderCode;
    private Long customerId;
    private String customerName;
    private Long deliveryStopId;
    private BigDecimal totalWeightKg;
}
