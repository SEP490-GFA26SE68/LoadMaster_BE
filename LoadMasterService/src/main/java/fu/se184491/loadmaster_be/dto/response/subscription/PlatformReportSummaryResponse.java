package fu.se184491.loadmaster_be.dto.response.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformReportSummaryResponse {
    private long totalCompanies;
    private Map<SubscriptionTier, Long> companiesByTier;
    private long activeContracts;
    private long expiringSoonContracts;
    private BigDecimal totalRevenue;
}
