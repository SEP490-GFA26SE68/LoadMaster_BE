package fu.se184491.loadmaster_be.repository.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GuestRegistrationSessionRepository extends JpaRepository<GuestRegistrationSession, Long> {

    Optional<GuestRegistrationSession> findByToken(String token);

    Optional<GuestRegistrationSession> findByPaymentRef(String paymentRef);

    Optional<GuestRegistrationSession> findFirstByEmailAndStatusInOrderByCreatedAtDesc(
            String email,
            List<RegistrationStatus> statuses
    );

    List<GuestRegistrationSession> findAllByExpiresAtBeforeAndStatusNot(
            LocalDateTime now,
            RegistrationStatus status
    );
}
