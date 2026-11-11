package fu.se184491.loadmaster_be.dto.response.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gia hạn gói của công ty: gói đang (hoặc vừa) dùng, có gia hạn được không và vì sao, cùng thanh toán gói còn chờ.
 * {@code reason}: {@code NO_SUBSCRIPTION} chưa từng có gói, {@code CANCELLED} hợp đồng đã huỷ, {@code TRIAL} gói dùng thử,
 * {@code NOT_DUE} gói còn hạn ngoài {@code renewalWindowDays} ngày cuối; {@code null} khi gia hạn được.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionRenewalResponse {
    private Long planId;
    private String planName;
    private SubscriptionTier tier;
    /** Gói còn đang bán không — gói ngừng bán vẫn gia hạn được cho công ty đang dùng. */
    private Boolean planActive;
    private SubscriptionStatus status;
    private LocalDateTime expiresAt;
    private BigDecimal amountVnd;
    private Boolean renewable;
    private String reason;
    private Integer renewalWindowDays;
    private PendingPayment pendingPayment;

    /** Thanh toán gói (đăng ký hay gia hạn) còn chờ trả gần nhất của công ty. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PendingPayment {
        private Long id;
        private String transactionId;
        private String paymentUrl;
        /** {@code SUBSCRIPTION}, {@code SUBSCRIPTION_YEARLY} hoặc {@code SUBSCRIPTION_RENEWAL}. */
        private String orderType;
        private Long planId;
        private BigDecimal amountVnd;
        private LocalDateTime createdAt;
    }
}
