package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ghi phiên đăng ký hết hạn trong transaction riêng: lệnh gọi sẽ báo lỗi và rollback transaction của nó. */
@Component
@RequiredArgsConstructor
public class GuestRegistrationSessionExpiry {

    private final GuestRegistrationSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getStatus() != RegistrationStatus.COMPLETED) {
                session.setStatus(RegistrationStatus.EXPIRED);
                sessionRepository.save(session);
            }
        });
    }
}
