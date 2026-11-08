package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.entity.support.TrialRegistrationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface TrialRegistrationSessionRepository extends JpaRepository<TrialRegistrationSession, Long> {
    Optional<TrialRegistrationSession> findByToken(String token);
    Optional<TrialRegistrationSession> findFirstByEmailOrderByCreatedAtDesc(String email);
    void deleteByExpiresAtBefore(Instant now);
}
