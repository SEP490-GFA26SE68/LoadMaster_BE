package fu.se184491.loadmaster_be.config.scheduling;

import fu.se184491.loadmaster_be.service.account.UserService;
import fu.se184491.loadmaster_be.service.company.TrialAgreementEnforcementJob;
import fu.se184491.loadmaster_be.service.monitoring.ExceptionEscalationJob;
import fu.se184491.loadmaster_be.service.registration.ExpiredRegistrationSessionCleanupJob;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionRenewalJob;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lịch của mọi job định kỳ. Mỗi lượt chỉ một máy chạy (xem {@link SingleInstanceJobRunner}); việc của job nằm ở
 * bean của nó và được gọi qua proxy nên giữ nguyên transaction của bean đó.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobTriggers {

    static final String TRIAL_AGREEMENT = "trial-agreement-enforcement";
    static final String EXCEPTION_ESCALATION = "trip-exception-escalation";
    static final String REGISTRATION_CLEANUP = "expired-registration-cleanup";
    static final String SUBSCRIPTION_RENEWAL = "subscription-renewal";
    static final String TICKET_CHAT_CLEANUP = "closed-ticket-chat-cleanup";
    static final String INACTIVE_USER_PURGE = "inactive-user-purge";

    private final SingleInstanceJobRunner runner;
    private final TrialAgreementEnforcementJob trialAgreementEnforcementJob;
    private final ExceptionEscalationJob exceptionEscalationJob;
    private final ExpiredRegistrationSessionCleanupJob expiredRegistrationSessionCleanupJob;
    private final SubscriptionRenewalJob subscriptionRenewalJob;
    private final SupportTicketService supportTicketService;
    private final UserService userService;

    /** Mỗi 30 phút. */
    @Scheduled(cron = "0 0/30 * * * ?")
    public void enforceTrialAgreements() {
        runner.runExclusively(TRIAL_AGREEMENT, trialAgreementEnforcementJob::enforceUnsignedAgreements);
    }

    /** Sự cố OPEN quá 30 phút chưa xử lý → ESCALATED; mỗi phút. */
    @Scheduled(fixedDelayString = "PT1M")
    public void escalateOverdueExceptions() {
        runner.runExclusively(EXCEPTION_ESCALATION, exceptionEscalationJob::escalateOverdue);
    }

    /** Mỗi giờ. */
    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredRegistrations() {
        runner.runExclusively(REGISTRATION_CLEANUP, expiredRegistrationSessionCleanupJob::cleanupExpiredSessions);
    }

    /** 01:00 mỗi ngày. */
    @Scheduled(cron = "0 0 1 * * ?")
    public void renewSubscriptions() {
        runner.runExclusively(SUBSCRIPTION_RENEWAL, subscriptionRenewalJob::runRenewal);
    }

    /** Phút 15 mỗi giờ. */
    @Scheduled(cron = "0 15 * * * *")
    public void cleanupClosedTicketChats() {
        runner.runExclusively(TICKET_CHAT_CLEANUP, () -> {
            int cleaned = supportTicketService.cleanupClosedTicketChatHistory();
            if (cleaned > 0) {
                log.info("Cleared chat history of {} closed support tickets", cleaned);
            }
        });
    }

    /** Xoá hẳn tài khoản INACTIVE quá 3 tháng; mỗi giờ. */
    @Scheduled(cron = "0 0 * * * *")
    public void purgeInactiveUsers() {
        runner.runExclusively(INACTIVE_USER_PURGE, userService::purgeInactiveUsersAfterThreeMonths);
    }
}
