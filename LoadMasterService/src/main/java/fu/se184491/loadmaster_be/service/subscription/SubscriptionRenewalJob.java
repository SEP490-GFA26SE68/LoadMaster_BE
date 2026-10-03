package fu.se184491.loadmaster_be.service.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRenewalJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 0 1 * * ?") // 01:00 AM every day
    public void runRenewal() {
        log.info("Starting daily SubscriptionRenewalJob...");
        try {
            subscriptionService.autoRenewExpiringSubscriptions();
            log.info("SubscriptionRenewalJob completed successfully.");
        } catch (Exception ex) {
            log.error("Error running SubscriptionRenewalJob: {}", ex.getMessage(), ex);
        }
    }
}
