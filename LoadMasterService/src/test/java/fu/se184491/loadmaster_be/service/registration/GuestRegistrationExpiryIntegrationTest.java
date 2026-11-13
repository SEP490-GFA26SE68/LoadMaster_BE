package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Không đặt @Transactional: cần thấy đúng việc service commit. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:guest-expiry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret"
})
class GuestRegistrationExpiryIntegrationTest {

    @Autowired
    private GuestRegistrationService guestRegistrationService;

    @Autowired
    private GuestRegistrationSessionRepository sessionRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void expiredSessionStaysExpiredAfterInitPaymentFails() {
        GuestRegistrationSession session = sessionRepository.save(GuestRegistrationSession.builder()
                .token("expired-token")
                .email("expired@guest.test")
                .fullName("Guest Expired")
                .companyName("Expired Logistics")
                .companyCode("EXPIRED")
                .taxCode("0101234567")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .createdAt(LocalDateTime.now().minusHours(4))
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build());

        assertThatThrownBy(() -> guestRegistrationService.initPayment(
                new InitPaymentRequest("expired-token", 1L, BillingCycle.MONTHLY), "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRATION_SESSION_EXPIRED);

        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(RegistrationStatus.EXPIRED);
    }
}
