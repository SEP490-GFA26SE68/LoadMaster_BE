package fu.se184491.loadmaster_be.repository.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GuestRegistrationSessionRepository extends JpaRepository<GuestRegistrationSession, Long> {

    Optional<GuestRegistrationSession> findByToken(String token);

    Optional<GuestRegistrationSession> findByPaymentRef(String paymentRef);

    /** Khoá phiên đăng ký theo mã thanh toán để callback trả về và IPN không cùng tạo công ty. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GuestRegistrationSession s where s.paymentRef = :ref")
    Optional<GuestRegistrationSession> findByPaymentRefForUpdate(@Param("ref") String paymentRef);

    Optional<GuestRegistrationSession> findFirstByEmailAndStatusInOrderByCreatedAtDesc(
            String email,
            List<RegistrationStatus> statuses
    );

    List<GuestRegistrationSession> findAllByExpiresAtBeforeAndStatusNot(
            LocalDateTime now,
            RegistrationStatus status
    );
}

