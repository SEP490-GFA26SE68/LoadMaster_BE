package fu.se184491.loadmaster_be.service.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExceptionEscalationJob {

    private final TripExceptionService tripExceptionService;

    /** Sự cố OPEN quá 30 phút chưa xử lý → ESCALATED (TIMEOUT). */
    @Scheduled(fixedDelayString = "PT1M")
    public void escalateOverdue() {
        try {
            int count = tripExceptionService.escalateOverdue();
            if (count > 0) {
                log.info("Escalated {} overdue trip exceptions", count);
            }
        } catch (Exception ex) {
            log.error("Error running ExceptionEscalationJob: {}", ex.getMessage(), ex);
        }
    }
}
