package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.dto.request.registration.GuestRegistrationInitRequest;
import fu.se184491.loadmaster_be.dto.response.registration.GuestRegistrationSessionResponse;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.registration.impl.GuestRegistrationServiceImpl;
import fu.se184491.loadmaster_be.service.security.OtpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestRegistrationServiceTest {

    @Mock
    private GuestRegistrationSessionRepository sessionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private CompanyAgreementRepository companyAgreementRepository;
    @Mock
    private CreditService creditService;
    @Mock
    private KeycloakAdminClient keycloakAdminClient;
    @Mock
    private VnPayClient vnPayClient;
    @Mock
    private BrevoClient brevoClient;
    @Mock
    private OtpHasher otpHasher;
    @Mock
    private TemporaryPasswordGenerator temporaryPasswordGenerator;
    @Mock
    private GuestRegistrationSessionExpiry sessionExpiry;
    @Mock
    private fu.se184491.loadmaster_be.service.billing.InvoiceService invoiceService;

    private GuestRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GuestRegistrationServiceImpl(
                sessionRepository,
                userRepository,
                companyRepository,
                subscriptionPlanRepository,
                companySubscriptionRepository,
                paymentTransactionRepository,
                companyAgreementRepository,
                creditService,
                keycloakAdminClient,
                vnPayClient,
                brevoClient,
                otpHasher,
                temporaryPasswordGenerator,
                sessionExpiry,
                invoiceService
        );
    }

    private GuestRegistrationInitRequest buildValidInitRequest() {
        return GuestRegistrationInitRequest.builder()
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .phoneNumber("0987654321")
                .companyName("ABC Logistics")
                .companyCode("ABC_LOG")
                .taxCode("0101234567")
                .build();
    }

    @Test
    @DisplayName("initRegistration: thành công tạo session mới, gửi OTP và trả về token")
    void initRegistration_success() {
        GuestRegistrationInitRequest request = buildValidInitRequest();

        when(userRepository.existsByEmail("guest@company.com")).thenReturn(false);
        when(companyRepository.existsByCompanyCode("ABC_LOG")).thenReturn(false);
        when(companyRepository.existsByTaxCode("0101234567")).thenReturn(false);
        when(sessionRepository.findFirstByEmailAndStatusInOrderByCreatedAtDesc(eq("guest@company.com"), anyList()))
                .thenReturn(Optional.empty());
        when(otpHasher.hash(eq("guest@company.com"), anyString())).thenReturn("hashed_otp");
        when(sessionRepository.save(any(GuestRegistrationSession.class))).thenAnswer(invocation -> {
            GuestRegistrationSession session = invocation.getArgument(0);
            session.setId(1L);
            session.prePersist();
            return session;
        });

        GuestRegistrationSessionResponse response = service.initRegistration(request);

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getEmail()).isEqualTo("guest@company.com");
        assertThat(response.getStatus()).isEqualTo(RegistrationStatus.PENDING_EMAIL_VERIFY);
        assertThat(response.getCompanyName()).isEqualTo("ABC Logistics");

        ArgumentCaptor<GuestRegistrationSession> sessionCaptor = ArgumentCaptor.forClass(GuestRegistrationSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        GuestRegistrationSession savedSession = sessionCaptor.getValue();
        assertThat(savedSession.getOtpHash()).isEqualTo("hashed_otp");
        assertThat(savedSession.getOtpExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(savedSession.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(2));

        verify(brevoClient).sendEmail(eq("guest@company.com"), contains("OTP"), anyString());
    }

    @Test
    @DisplayName("initRegistration: thất bại khi email đã tồn tại trong users")
    void initRegistration_emailAlreadyExists_throwsException() {
        GuestRegistrationInitRequest request = buildValidInitRequest();
        when(userRepository.existsByEmail("guest@company.com")).thenReturn(true);

        assertThatThrownBy(() -> service.initRegistration(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);

        verify(sessionRepository, never()).save(any());
        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("initRegistration: thất bại khi company code đã tồn tại")
    void initRegistration_companyCodeAlreadyExists_throwsException() {
        GuestRegistrationInitRequest request = buildValidInitRequest();
        when(userRepository.existsByEmail("guest@company.com")).thenReturn(false);
        when(companyRepository.existsByCompanyCode("ABC_LOG")).thenReturn(true);

        assertThatThrownBy(() -> service.initRegistration(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMPANY_ALREADY_EXISTS);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("initRegistration: thất bại khi tax code đã tồn tại")
    void initRegistration_taxCodeAlreadyExists_throwsException() {
        GuestRegistrationInitRequest request = buildValidInitRequest();
        when(userRepository.existsByEmail("guest@company.com")).thenReturn(false);
        when(companyRepository.existsByCompanyCode("ABC_LOG")).thenReturn(false);
        when(companyRepository.existsByTaxCode("0101234567")).thenReturn(true);

        assertThatThrownBy(() -> service.initRegistration(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TAX_CODE_ALREADY_EXISTS);

        verify(sessionRepository, never()).save(any());
    }

    // =========================================================================
    // SLICE 2: verifyOtp & resendOtp
    // =========================================================================

    @Test
    @DisplayName("verifyOtp: thành công chuyển trạng thái sang EMAIL_VERIFIED")
    void verifyOtp_success() {
        fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "123456");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .companyName("ABC Logistics")
                .companyCode("ABC_LOG")
                .taxCode("0101234567")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpHash("hashed_123456")
                .otpExpiresAt(LocalDateTime.now().plusMinutes(5))
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("guest@company.com", "123456")).thenReturn("hashed_123456");
        when(sessionRepository.save(any(GuestRegistrationSession.class))).thenAnswer(i -> i.getArgument(0));

        GuestRegistrationSessionResponse response = service.verifyOtp(request);

        assertThat(response.getStatus()).isEqualTo(RegistrationStatus.EMAIL_VERIFIED);
        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.EMAIL_VERIFIED);
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("verifyOtp: thất bại khi token không tồn tại")
    void verifyOtp_tokenNotFound_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("non-existent", "123456");

        when(sessionRepository.findByToken("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRATION_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("verifyOtp: thất bại khi sai trạng thái")
    void verifyOtp_invalidStatus_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "123456");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.EMAIL_VERIFIED) // already verified
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRATION_INVALID_STATUS);
    }

    @Test
    @DisplayName("verifyOtp: thất bại khi OTP đã hết hạn")
    void verifyOtp_otpExpired_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "123456");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpHash("hashed_123456")
                .otpExpiresAt(LocalDateTime.now().minusMinutes(1)) // expired!
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.verifyOtp(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_EXPIRED);
    }

    @Test
    @DisplayName("verifyOtp: thất bại khi OTP sai")
    void verifyOtp_otpIncorrect_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "999999");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpHash("hashed_123456")
                .otpExpiresAt(LocalDateTime.now().plusMinutes(5))
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("guest@company.com", "999999")).thenReturn("hashed_wrong");

        assertThatThrownBy(() -> service.verifyOtp(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INCORRECT);
    }

    @Test
    @DisplayName("verifyOtp: sai lần thứ 5 thì huỷ mã và báo OTP_ATTEMPTS_EXCEEDED; lần sau vẫn bị chặn dù đúng mã")
    void verifyOtp_fifthWrongAttempt_invalidatesOtp() {
        var wrong = new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "999999");
        var right = new fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest("valid-token", "123456");
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpHash("hashed_123456")
                .otpFailedAttempts(3)
                .otpExpiresAt(LocalDateTime.now().plusMinutes(5))
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();
        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash("guest@company.com", "999999")).thenReturn("hashed_wrong");

        assertThatThrownBy(() -> service.verifyOtp(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INCORRECT);
        assertThat(session.getOtpFailedAttempts()).isEqualTo(4);

        assertThatThrownBy(() -> service.verifyOtp(wrong))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_ATTEMPTS_EXCEEDED);
        assertThat(session.getOtpFailedAttempts()).isEqualTo(5);
        assertThat(session.getOtpHash()).isNull();
        verify(sessionRepository, times(2)).save(session);

        assertThatThrownBy(() -> service.verifyOtp(right))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_ATTEMPTS_EXCEEDED);
        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.PENDING_EMAIL_VERIFY);
    }

    @Test
    @DisplayName("verifyOtp, resendOtp, acceptPolicy giữ lại thay đổi khi ném lỗi nghiệp vụ (EXPIRED, bộ đếm sai)")
    void businessErrorsDoNotRollBackSessionWrites() throws Exception {
        Class<GuestRegistrationServiceImpl> type = GuestRegistrationServiceImpl.class;
        for (var method : java.util.List.of(
                type.getMethod("verifyOtp", fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest.class),
                type.getMethod("resendOtp", String.class),
                type.getMethod("acceptPolicy", fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest.class, String.class))) {
            var tx = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.noRollbackFor()).as(method.getName()).contains(AppException.class);
        }
    }

    @Test
    @DisplayName("resendOtp: thành công gửi lại mã OTP mới")
    void resendOtp_success() {
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpResendAllowedAt(LocalDateTime.now().minusSeconds(10)) // cooldown passed
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(otpHasher.hash(eq("guest@company.com"), anyString())).thenReturn("new_hash");
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        fu.se184491.loadmaster_be.dto.response.registration.ResendOtpResponse response = service.resendOtp("valid-token");

        assertThat(response).isNotNull();
        assertThat(response.getOtpExpiresAt()).isAfter(LocalDateTime.now());
        verify(brevoClient).sendEmail(eq("guest@company.com"), contains("OTP"), anyString());
    }

    @Test
    @DisplayName("resendOtp: thất bại khi chưa hết 60s cooldown")
    void resendOtp_cooldownActive_throwsException() {
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .otpResendAllowedAt(LocalDateTime.now().plusSeconds(30)) // still in cooldown
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.resendOtp("valid-token"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRATION_RESEND_COOLDOWN);

        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }

    // =========================================================================
    // SLICE 3: acceptPolicy & getSessionStatus
    // =========================================================================

    @Test
    @DisplayName("acceptPolicy: thành công chuyển trạng thái sang POLICY_ACCEPTED")
    void acceptPolicy_success() {
        fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest("valid-token");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.EMAIL_VERIFIED)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GuestRegistrationSession.class))).thenAnswer(i -> i.getArgument(0));

        GuestRegistrationSessionResponse response = service.acceptPolicy(request, "192.168.1.1");

        assertThat(response.getStatus()).isEqualTo(RegistrationStatus.POLICY_ACCEPTED);
        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.POLICY_ACCEPTED);
        assertThat(session.getPolicyAcceptedAt()).isNotNull();
        assertThat(session.getIpAddress()).isEqualTo("192.168.1.1");
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("acceptPolicy: thất bại khi session chưa xác thực email")
    void acceptPolicy_invalidStatus_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest("valid-token");

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.acceptPolicy(request, "192.168.1.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRATION_INVALID_STATUS);
    }

    @Test
    @DisplayName("getSessionStatus: thành công trả về thông tin trạng thái session")
    void getSessionStatus_success() {
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .companyName("ABC Logistics")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        GuestRegistrationSessionResponse response = service.getSessionStatus("valid-token");

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("valid-token");
        assertThat(response.getStatus()).isEqualTo(RegistrationStatus.POLICY_ACCEPTED);
    }

    // =========================================================================
    // SLICE 4: initPayment
    // =========================================================================

    @Test
    @DisplayName("initPayment: thành công tạo link thanh toán VNPay và chuyển sang PAYMENT_PENDING")
    void initPayment_success() {
        fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest(
                        "valid-token",
                        1L,
                        fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY
                );

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan plan = fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan.builder()
                .id(1L)
                .planCode("PRO_MONTHLY")
                .name("Gói Chuyên Nghiệp")
                .price(java.math.BigDecimal.valueOf(2_000_000))
                .active(true)
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(vnPayClient.createPaymentUrl(anyString(), any(java.math.BigDecimal.class), anyString(), anyString()))
                .thenReturn("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=123");
        when(sessionRepository.save(any(GuestRegistrationSession.class))).thenAnswer(i -> i.getArgument(0));

        fu.se184491.loadmaster_be.dto.response.registration.PaymentLinkResponse response =
                service.initPayment(request, "127.0.0.1");

        assertThat(response).isNotNull();
        assertThat(response.getPaymentUrl()).contains("sandbox.vnpayment.vn");
        assertThat(response.getAmountVnd()).isEqualByComparingTo(java.math.BigDecimal.valueOf(2_000_000));
        assertThat(response.getPaymentRef()).isNotBlank();

        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.PAYMENT_PENDING);
        assertThat(session.getPlan()).isEqualTo(plan);
        assertThat(session.getBillingCycle()).isEqualTo(fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY);
        assertThat(session.getPaymentGateway()).isEqualTo(fu.se184491.loadmaster_be.constant.billing.PaymentGateway.VNPAY);
        assertThat(session.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(2));
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("initPayment: phiên quá hạn được ghi EXPIRED trong transaction riêng rồi mới báo lỗi")
    void initPayment_expiredSession_marksExpiredInOwnTransaction() {
        fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest(
                        "old-token",
                        1L,
                        fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY
                );
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .id(77L)
                .token("old-token")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(sessionRepository.findByToken("old-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.initPayment(request, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRATION_SESSION_EXPIRED);
        verify(sessionExpiry).markExpired(77L);
        verifyNoInteractions(vnPayClient);
    }

    @Test
    @DisplayName("initPayment: thất bại khi session chưa ký thỏa thuận")
    void initPayment_invalidStatus_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest(
                        "valid-token",
                        1L,
                        fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY
                );

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.EMAIL_VERIFIED) // not POLICY_ACCEPTED
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.initPayment(request, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRATION_INVALID_STATUS);
    }

    @Test
    @DisplayName("initPayment: thất bại khi không tìm thấy gói cước")
    void initPayment_planNotFound_throwsException() {
        fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest request =
                new fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest(
                        "valid-token",
                        999L,
                        fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY
                );

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        when(sessionRepository.findByToken("valid-token")).thenReturn(Optional.of(session));
        when(subscriptionPlanRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initPayment(request, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND);
    }

    // =========================================================================
    // SLICE 5: handlePaymentCallback (Account creation, PDF invoice, email)
    // =========================================================================

    @Test
    @DisplayName("handlePaymentCallback: thanh toán thành công tạo Company, User, Subscription, Agreement, gửi hóa đơn PDF và email")
    void handlePaymentCallback_success() {
        fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan plan = fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan.builder()
                .id(1L)
                .planCode("PRO_MONTHLY")
                .name("Gói Chuyên Nghiệp")
                .price(java.math.BigDecimal.valueOf(2_000_000))
                .monthlyCredits(500)
                .active(true)
                .build();

        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("valid-token")
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .phoneNumber("0987654321")
                .companyName("ABC Logistics")
                .companyCode("ABC_LOG")
                .taxCode("0101234567")
                .plan(plan)
                .billingCycle(fu.se184491.loadmaster_be.constant.billing.BillingCycle.MONTHLY)
                .amountVnd(java.math.BigDecimal.valueOf(2_000_000))
                .paymentRef("REG_12345")
                .status(RegistrationStatus.PAYMENT_PENDING)
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("vnp_TxnRef", "REG_12345");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_Amount", "200000000"); // 2,000,000 * 100
        params.put("vnp_SecureHash", "mock_hash");

        when(sessionRepository.findByPaymentRefForUpdate("REG_12345")).thenReturn(Optional.of(session));
        when(companyRepository.save(any(fu.se184491.loadmaster_be.entity.company.Company.class))).thenAnswer(i -> {
            fu.se184491.loadmaster_be.entity.company.Company comp = i.getArgument(0);
            comp.setId(10L);
            return comp;
        });
        when(keycloakAdminClient.findUserIdByEmail("guest@company.com")).thenReturn(null);
        when(keycloakAdminClient.createUser("guest@company.com", "Nguyen Van A")).thenReturn("keycloak-user-uuid");
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPass@123");
        when(userRepository.save(any(fu.se184491.loadmaster_be.entity.account.User.class))).thenAnswer(i -> {
            fu.se184491.loadmaster_be.entity.account.User u = i.getArgument(0);
            u.setId(20L);
            return u;
        });

        java.util.Map<String, String> result = service.handlePaymentCallback(params);

        assertThat(result.get("RspCode")).isEqualTo("00");
        assertThat(result.get("Message")).isEqualTo("Confirm Success");

        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.COMPLETED);

        // Verify company created
        verify(companyRepository).save(any(fu.se184491.loadmaster_be.entity.company.Company.class));

        // Verify Keycloak user created and password set
        verify(keycloakAdminClient).createUser("guest@company.com", "Nguyen Van A");
        verify(keycloakAdminClient).setPassword("keycloak-user-uuid", "TempPass@123", false);
        verify(keycloakAdminClient).assignRealmRole("keycloak-user-uuid", "ADMIN");

        // Verify user created
        verify(userRepository).save(any(fu.se184491.loadmaster_be.entity.account.User.class));

        // Verify subscription created
        verify(companySubscriptionRepository).save(any(fu.se184491.loadmaster_be.entity.billing.CompanySubscription.class));

        // Verify credit granted
        verify(creditService).grantMonthlyCredits(eq(10L), eq(500));

        // Verify agreement created
        verify(companyAgreementRepository).save(any(fu.se184491.loadmaster_be.entity.company.CompanyAgreement.class));

        // Verify payment transaction saved
        verify(paymentTransactionRepository).save(any(fu.se184491.loadmaster_be.entity.billing.PaymentTransaction.class));

        // Verify email sent with PDF attachment
        verify(brevoClient).sendEmailWithAttachment(
                eq("guest@company.com"),
                contains("Thông tin tài khoản"),
                contains("TempPass@123"),
                contains(".pdf"),
                any(byte[].class)
        );
    }

    @Test
    @DisplayName("handlePaymentCallback: thất bại khi không tìm thấy đơn")
    void handlePaymentCallback_orderNotFound() {
        java.util.Map<String, String> params = java.util.Map.of(
                "vnp_TxnRef", "REG_NOT_FOUND"
        );

        when(sessionRepository.findByPaymentRefForUpdate("REG_NOT_FOUND")).thenReturn(Optional.empty());

        java.util.Map<String, String> result = service.handlePaymentCallback(params);

        assertThat(result.get("RspCode")).isEqualTo("01");
        assertThat(result.get("Message")).isEqualTo("Order not Found");
    }

    @Test
    @DisplayName("handlePaymentCallback: idempotent khi đã completed trước đó")
    void handlePaymentCallback_alreadyCompleted() {
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .paymentRef("REG_12345")
                .status(RegistrationStatus.COMPLETED)
                .build();

        java.util.Map<String, String> params = java.util.Map.of(
                "vnp_TxnRef", "REG_12345"
        );

        when(sessionRepository.findByPaymentRefForUpdate("REG_12345")).thenReturn(Optional.of(session));

        java.util.Map<String, String> result = service.handlePaymentCallback(params);

        assertThat(result.get("RspCode")).isEqualTo("00");
        assertThat(result.get("Message")).isEqualTo("Confirm Success");

        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("handlePaymentCallback: khi vnp_ResponseCode khác 00 thì cập nhật PAYMENT_FAILED và gửi thông báo")
    void handlePaymentCallback_paymentFailedCode() {
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .paymentRef("REG_12345")
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .status(RegistrationStatus.PAYMENT_PENDING)
                .build();

        java.util.Map<String, String> params = java.util.Map.of(
                "vnp_TxnRef", "REG_12345",
                "vnp_ResponseCode", "24" // Customer cancelled
        );

        when(sessionRepository.findByPaymentRefForUpdate("REG_12345")).thenReturn(Optional.of(session));

        java.util.Map<String, String> result = service.handlePaymentCallback(params);

        assertThat(result.get("RspCode")).isEqualTo("00");
        assertThat(session.getStatus()).isEqualTo(RegistrationStatus.PAYMENT_FAILED);

        verify(brevoClient).sendEmail(eq("guest@company.com"), contains("không thành công"), anyString());
    }
}
