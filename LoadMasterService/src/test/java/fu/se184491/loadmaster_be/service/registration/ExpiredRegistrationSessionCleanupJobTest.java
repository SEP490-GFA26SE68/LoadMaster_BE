package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpiredRegistrationSessionCleanupJobTest {

    @Mock
    private GuestRegistrationSessionRepository sessionRepository;

    @InjectMocks
    private ExpiredRegistrationSessionCleanupJob cleanupJob;

    @Test
    @DisplayName("cleanupExpiredSessions: cập nhật trạng thái EXPIRED cho các phiên quá hạn 3h")
    void cleanupExpiredSessions_success() {
        GuestRegistrationSession session1 = GuestRegistrationSession.builder()
                .id(1L)
                .token("tok-1")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();

        GuestRegistrationSession session2 = GuestRegistrationSession.builder()
                .id(2L)
                .token("tok-2")
                .status(RegistrationStatus.PAYMENT_PENDING)
                .expiresAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(sessionRepository.findAllByExpiresAtBeforeAndStatusNot(any(LocalDateTime.class), eq(RegistrationStatus.COMPLETED)))
                .thenReturn(List.of(session1, session2));

        cleanupJob.cleanupExpiredSessions();

        assertThat(session1.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(session2.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);

        verify(sessionRepository).saveAll(List.of(session1, session2));
    }
}

