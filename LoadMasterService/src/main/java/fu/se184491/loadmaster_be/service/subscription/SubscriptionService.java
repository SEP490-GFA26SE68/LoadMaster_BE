package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionRenewalResponse;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;

public interface SubscriptionService {
    SubscriptionCurrentResponse getCurrentSubscription(Long companyId);
    PaymentUrlResponse subscribe(Long companyId, SubscribeRequest request, String ipAddress);
    void activateSubscription(Long companyId, Long planId);
    default void activateSubscription(Long companyId, Long planId, BillingCycle billingCycle) {
        activateSubscription(companyId, planId);
    }
    void cancelSubscription(Long companyId);
    /** Gói của công ty có gia hạn được không, kèm thanh toán gói còn chờ. */
    SubscriptionRenewalResponse getRenewal(Long companyId);
    /** Gia hạn đúng gói đang (hoặc vừa) dùng thêm một kỳ: tạo thanh toán, hoặc dùng lại thanh toán gia hạn còn chờ. */
    PaymentUrlResponse renew(Long companyId, PaymentGateway gateway, String ipAddress);
    void autoRenewExpiringSubscriptions();
}
