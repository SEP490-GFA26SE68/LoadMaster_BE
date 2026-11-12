package fu.se184491.loadmaster_be.dto.response.credit;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Giá và luật credit của công ty đang gọi: giá một credit khi nạp theo hạng gói hiện tại (chưa có gói tính như Basic),
 * bảng giá của mọi hạng, số credit một lần tối ưu 3D và một lần chạy lại, giới hạn nạp mà máy chủ kiểm.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPricingResponse {
    /** Hạng dùng để tính giá; công ty chưa có gói là BASIC. */
    private SubscriptionTier tier;
    private BigDecimal pricePerCreditVnd;
    private Map<SubscriptionTier, BigDecimal> tierPrices;
    /** Credit một lần tối ưu 3D (ba phương án ứng viên). */
    private Integer optimizationCost;
    /** Credit một lần chạy lại một phương án. */
    private Integer rerunCost;
    private Integer minTopUpCredits;
    /** {@code null}: không có trần. */
    private Integer maxTopUpCredits;
    /** Hạng không giới hạn: lần tối ưu không bị chặn theo số dư. */
    private Boolean unlimited;
    /** Nạp được không: gói đang hoạt động, hoặc công ty chưa có gói. */
    private Boolean topUpAllowed;
    /** Số dư sau một lần trừ xuống tới mức này thì báo "Sắp hết credit". */
    private Integer lowBalanceThreshold;
}
