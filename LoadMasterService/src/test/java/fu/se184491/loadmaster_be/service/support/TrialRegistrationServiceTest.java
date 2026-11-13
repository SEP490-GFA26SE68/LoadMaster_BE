package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialInitRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialResendOtpRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialVerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialInitResponse;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialVerifyResponse;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.entity.support.TrialRegistrationSession;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CompanyCodeGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.repository.support.TrialRegistrationSessionRepository;
import fu.se184491.loadmaster_be.service.security.OtpHasher;
import fu.se184491.loadmaster_be.service.support.impl.TrialRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrialRegistrationServiceTest {

    @Mock
    private TrialRegistrationSessionRepository sessionRepository;

    @Mock
    private TrialAccountRequestRepository trialAccountRequestRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyCodeGenerator companyCodeGenerator;

    @Mock
    private OtpHasher otpHasher;

    @Mock
    private BrevoClient brevoClient;

    @InjectMocks
    private TrialRegistrationServiceImpl trialRegistrationService;

    private GuestTrialInitRequest initRequest;
    private TrialRegistrationSession session;

    @BeforeEach
    void setUp() {
        initRequest = GuestTrialInitRequest.builder()
                .fullName("Nguyen Van A")
                .email("test@example.com")
                .phoneNumber("0912345678")
                .companyName("Cong Ty TNHH Test")
                .taxCode("0123456789")
                .companyAddress("123 Duong ABC, Quan 1, TP HCM")
                .interestedPlanId(1L)
                .note("Muon trai nghiem thu")
                .build();

        session = TrialRegistrationSession.builder()
                .id(1L)
                .token("test-session-token")
                .email("test@example.com")
                .fullName("Nguyen Van A")
                .phoneNumber("0912345678")
                .companyName("Cong Ty TNHH Test")
                .taxCode("0123456789")
                .companyAddress("123 Duong ABC, Quan 1, TP HCM")
                .otpHash("hashed-otp-123456")
                .createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .otpExpiresAt(LocalDateTime.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS))
                .otpResendAllowedAt(LocalDateTime.now().minus(5, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS))
                .expiresAt(LocalDateTime.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
                .verified(false)
                .build();
    }

    @Test
    @DisplayName("initTrialRegistration - generates session with hashed OTP and sends email")
    void initTrialRegistration_ShouldCreateSessionAndSendOtp() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(false);
        when(trialAccountRequestRepository.existsByTaxCodeAndStatusIn(eq("0123456789"), anyCollection())).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(trialAccountRequestRepository.existsByEmailAndStatusIn(eq("test@example.com"), anyCollection())).thenReturn(false);
        when(subscriptionPlanRepository.findByTierAndActiveTrue(SubscriptionTier.BASIC))
                .thenReturn(Optional.of(SubscriptionPlan.builder().id(1L).name("Basic").build()));
        when(otpHasher.hash(eq("test@example.com"), anyString())).thenReturn("hashed-otp");
        when(sessionRepository.save(any(TrialRegistrationSession.class))).thenAnswer(inv -> inv.getArgument(0));

        GuestTrialInitResponse response = trialRegistrationService.initTrialRegistration(initRequest);

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getMaskedEmail()).isNotBlank();
        assertThat(response.getOtpExpiresAt()).isNotNull();

        verify(companyRepository).existsByTaxCode("0123456789");
        verify(userRepository).existsByEmail("test@example.com");
        verify(sessionRepository).save(any(TrialRegistrationSession.class));
        verify(brevoClient).sendEmail(eq("test@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("initTrialRegistration - fails immediately when taxCode or email is not eligible")
    void initTrialRegistration_WhenNotEligible_ShouldThrowAndNotSendOtp() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(true);

        assertThatThrownBy(() -> trialRegistrationService.initTrialRegistration(initRequest))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TAX_CODE_ALREADY_EXISTS);

        verify(sessionRepository, never()).save(any());
        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("verifyOtp - fails when token not found or expired")
    void verifyOtp_WhenSessionNotFound_ShouldThrowException() {
        when(sessionRepository.findByToken("invalid-token")).thenReturn(Optional.empty());

        GuestTrialVerifyOtpRequest verifyRequest = GuestTrialVerifyOtpRequest.builder()
                .token("invalid-token")
                .otp("123456")
                .build();

        assertThatThrownBy(() -> trialRegistrationService.verifyOtp(verifyRequest))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("verifyOtp - fails when OTP is incorrect")
    void verifyOtp_WhenOtpIncorrect_ShouldThrowException() {
        when(sessionRepository.findByToken("test-session-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("test@example.com", "999999")).thenReturn("different-hash");

        GuestTrialVerifyOtpRequest verifyRequest = GuestTrialVerifyOtpRequest.builder()
                .token("test-session-token")
                .otp("999999")
                .build();

        assertThatThrownBy(() -> trialRegistrationService.verifyOtp(verifyRequest))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("verifyOtp - the 5th wrong OTP invalidates the code; resend issues a fresh one with the counter reset")
    void verifyOtp_FifthWrongAttempt_InvalidatesOtp() {
        session.setOtpFailedAttempts(4);
        when(sessionRepository.findByToken("test-session-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("test@example.com", "999999")).thenReturn("different-hash");
        GuestTrialVerifyOtpRequest wrong = GuestTrialVerifyOtpRequest.builder()
                .token("test-session-token").otp("999999").build();

        assertThatThrownBy(() -> trialRegistrationService.verifyOtp(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_ATTEMPTS_EXCEEDED);
        assertThat(session.getOtpHash()).isNull();
        assertThat(session.getOtpFailedAttempts()).isEqualTo(5);
        verify(sessionRepository).save(session);
        verify(trialAccountRequestRepository, never()).save(any());

        assertThatThrownBy(() -> trialRegistrationService.verifyOtp(GuestTrialVerifyOtpRequest.builder()
                .token("test-session-token").otp("123456").build()))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_ATTEMPTS_EXCEEDED);

        when(otpHasher.hash(eq("test@example.com"), anyString())).thenReturn("hashed-new");
        trialRegistrationService.resendOtp(GuestTrialResendOtpRequest.builder().token("test-session-token").build());
        assertThat(session.getOtpFailedAttempts()).isZero();
        assertThat(session.getOtpHash()).isEqualTo("hashed-new");
    }

    @Test
    @DisplayName("verifyOtp keeps the attempt counter when it throws a business error")
    void verifyOtp_DoesNotRollBackOnBusinessErrors() throws Exception {
        var tx = TrialRegistrationServiceImpl.class
                .getMethod("verifyOtp", GuestTrialVerifyOtpRequest.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.noRollbackFor()).contains(AppException.class);
    }

    @Test
    @DisplayName("verifyOtp - succeeds and invokes AI pre-validation")
    void verifyOtp_WhenOtpCorrect_ShouldCreateTrialRequestAndPreValidate() {
        when(sessionRepository.findByToken("test-session-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("test@example.com", "123456")).thenReturn("hashed-otp-123456");

        TrialAccountRequest createdRequest = TrialAccountRequest.builder()
                .id(100L)
                .fullName("Nguyen Van A")
                .email("test@example.com")
                .companyName("Cong Ty TNHH Test")
                .taxCode("0123456789")
                .status(TrialRequestStatus.PENDING)
                .aiPrecheckStatus(AiPrecheckStatus.PASSED)
                .build();

        when(trialAccountRequestRepository.save(any(TrialAccountRequest.class))).thenReturn(createdRequest);

        GuestTrialVerifyOtpRequest verifyRequest = GuestTrialVerifyOtpRequest.builder()
                .token("test-session-token")
                .otp("123456")
                .build();

        GuestTrialVerifyResponse response = trialRegistrationService.verifyOtp(verifyRequest);

        assertThat(response).isNotNull();
        assertThat(response.getRequestId()).isEqualTo(100L);
        assertThat(response.getStatus()).isEqualTo(TrialRequestStatus.PENDING);
        assertThat(response.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.PASSED);
        assertThat(session.getVerified()).isTrue();
        verify(trialAccountRequestRepository).save(any(TrialAccountRequest.class));
    }

    @Test
    @DisplayName("resendOtp - throws exception if cooldown is active")
    void resendOtp_WhenCooldownActive_ShouldThrowException() {
        session.setOtpResendAllowedAt(LocalDateTime.now().plus(30, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS));
        when(sessionRepository.findByToken("test-session-token")).thenReturn(Optional.of(session));

        GuestTrialResendOtpRequest resendRequest = GuestTrialResendOtpRequest.builder()
                .token("test-session-token")
                .build();

        assertThatThrownBy(() -> trialRegistrationService.resendOtp(resendRequest))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("initTrialRegistration - rejects VN phone number with 8 identical trailing digits like 0911111111")
    void initTrialRegistration_WhenVnPhoneHasRepeatedTrailingDigits_ShouldThrowInvalidPhoneNumber() {
        initRequest.setPhoneNumber("0911111111");

        assertThatThrownBy(() -> trialRegistrationService.initTrialRegistration(initRequest))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PHONE_NUMBER);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("initTrialRegistration - rejects phone number when it already exists in users table")
    void initTrialRegistration_WhenPhoneNumberAlreadyExistsInUsersTable_ShouldThrowPhoneNumberAlreadyExists() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(false);
        when(trialAccountRequestRepository.existsByTaxCodeAndStatusIn(eq("0123456789"), anyCollection())).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(trialAccountRequestRepository.existsByEmailAndStatusIn(eq("test@example.com"), anyCollection())).thenReturn(false);
        when(userRepository.existsByPhoneNumberIn(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> trialRegistrationService.initTrialRegistration(initRequest))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);

        verify(sessionRepository, never()).save(any());
        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }
}

