package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;

public interface SubscriptionService {
    SubscriptionCurrentResponse getCurrentSubscription(Long companyId);
    PaymentUrlResponse subscribe(Long companyId, SubscribeRequest request, String ipAddress);
    void activateSubscription(Long companyId, Long planId);
    void cancelSubscription(Long companyId);
    void autoRenewExpiringSubscriptions();
}
