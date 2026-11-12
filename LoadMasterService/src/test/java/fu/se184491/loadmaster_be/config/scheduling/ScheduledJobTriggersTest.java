package fu.se184491.loadmaster_be.config.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScheduledJobTriggersTest {

    private static Scheduled scheduledOf(String method) throws NoSuchMethodException {
        Scheduled scheduled = ScheduledJobTriggers.class.getMethod(method).getAnnotation(Scheduled.class);
        assertNotNull(scheduled, method);
        return scheduled;
    }

    @Test
    void ticketChatCleanup_runsHourlyNotEveryTenSeconds() throws Exception {
        Scheduled scheduled = scheduledOf("cleanupClosedTicketChats");
        assertEquals("0 15 * * * *", scheduled.cron());
        assertEquals(-1L, scheduled.fixedDelay());
    }

    @Test
    void schedulesKeepTheirPreviousCadence() throws Exception {
        assertEquals("0 0/30 * * * ?", scheduledOf("enforceTrialAgreements").cron());
        assertEquals("PT1M", scheduledOf("escalateOverdueExceptions").fixedDelayString());
        assertEquals("0 0 * * * *", scheduledOf("cleanupExpiredRegistrations").cron());
        assertEquals("0 0 1 * * ?", scheduledOf("renewSubscriptions").cron());
        assertEquals("0 0 * * * *", scheduledOf("purgeInactiveUsers").cron());
    }

    @Test
    void everyJobHasItsOwnLockKey() {
        List<String> names = List.of(
                ScheduledJobTriggers.TRIAL_AGREEMENT,
                ScheduledJobTriggers.EXCEPTION_ESCALATION,
                ScheduledJobTriggers.REGISTRATION_CLEANUP,
                ScheduledJobTriggers.SUBSCRIPTION_RENEWAL,
                ScheduledJobTriggers.TICKET_CHAT_CLEANUP,
                ScheduledJobTriggers.INACTIVE_USER_PURGE);
        Set<Integer> keys = names.stream().map(SingleInstanceJobRunner::lockKey).collect(Collectors.toSet());
        assertEquals(names.size(), keys.size());
    }

    @Test
    void onlyThisClassDeclaresSchedules() {
        long scheduled = Arrays.stream(ScheduledJobTriggers.class.getDeclaredMethods())
                .map((Method m) -> m.getAnnotation(Scheduled.class))
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(6, scheduled);
    }
}
