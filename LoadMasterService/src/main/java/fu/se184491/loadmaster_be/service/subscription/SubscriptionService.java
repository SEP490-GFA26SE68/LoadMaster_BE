package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionRenewalResponse;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.service.billing.InvoiceService.SubscriptionPeriod;

public interface SubscriptionService {
    SubscriptionCurrentResponse getCurrentSubscription(Long companyId);
    PaymentUrlResponse subscribe(Long companyId, SubscribeRequest request, String ipAddress);
    /** Kích hoạt hoặc kéo dài gói; trả kỳ vừa thêm (để ghi hoá đơn của thanh toán). */
    SubscriptionPeriod activateSubscription(Long companyId, Long planId);
    default SubscriptionPeriod activateSubscription(Long companyId, Long planId, BillingCycle billingCycle) {
        return activateSubscription(companyId, planId);
    }
    void cancelSubscription(Long companyId);
    /** Gói của công ty có gia hạn được không, kèm thanh toán gói còn chờ. */
    SubscriptionRenewalResponse getRenewal(Long companyId);
    /** Gia hạn đúng gói đang (hoặc vừa) dùng thêm một kỳ: tạo thanh toán, hoặc dùng lại thanh toán gia hạn còn chờ. */
    PaymentUrlResponse renew(Long companyId, PaymentGateway gateway, String ipAddress);
    void autoRenewExpiringSubscriptions();
}
