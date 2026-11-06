package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredRegistrationSessionCleanupJob {

    private final GuestRegistrationSessionRepository sessionRepository;

    @Scheduled(cron = "0 0 * * * *") // Run every hour
    @Transactional
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<GuestRegistrationSession> expiredSessions = sessionRepository
                .findAllByExpiresAtBeforeAndStatusNot(now, RegistrationStatus.COMPLETED);

        if (expiredSessions.isEmpty()) {
            return;
        }

        List<GuestRegistrationSession> sessionsToUpdate = expiredSessions.stream()
                .filter(s -> s.getStatus() != RegistrationStatus.EXPIRED)
                .peek(s -> s.setStatus(RegistrationStatus.EXPIRED))
                .toList();

        if (!sessionsToUpdate.isEmpty()) {
            sessionRepository.saveAll(sessionsToUpdate);
            log.info("Expired {} guest registration sessions that timed out", sessionsToUpdate.size());
        }
    }
}
