package fu.se184491.loadmaster_be.service.security;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String EMAIL = "an@fastmove.vn";

    @Mock private BrevoClient brevoClient;
    @Mock private OtpHasher otpHasher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> values;
    @Mock private UserRepository userRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks private PasswordResetService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(values);
    }

    private static void assertCode(ErrorCode expected, Executable call) {
        AppException ex = assertThrows(AppException.class, call);
        assertEquals(expected, ex.getErrorCode());
    }

    @Test
    @DisplayName("Gửi lại mã trong thời gian chờ: OTP_RESEND_COOLDOWN, không gửi email")
    void requestDuringCooldown() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);
        when(redisTemplate.hasKey("password-reset:cooldown:" + EMAIL)).thenReturn(true);

        assertCode(ErrorCode.OTP_RESEND_COOLDOWN, () -> service.requestOtp(EMAIL));
        verify(brevoClient, never()).sendPasswordResetOtp(anyString(), anyString());
    }

    @Test
    @DisplayName("Quá 3 lần yêu cầu trong 15 phút: OTP_REQUEST_LIMIT_EXCEEDED")
    void requestOverLimit() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);
        when(redisTemplate.hasKey("password-reset:cooldown:" + EMAIL)).thenReturn(false);
        when(values.increment("password-reset:request-rate:" + EMAIL)).thenReturn(4L);

        assertCode(ErrorCode.OTP_REQUEST_LIMIT_EXCEEDED, () -> service.requestOtp(EMAIL));
        verify(brevoClient, never()).sendPasswordResetOtp(anyString(), anyString());
    }

    @Test
    @DisplayName("Không còn mã trong Redis: OTP_EXPIRED")
    void verifyExpired() {
        when(values.get("password-reset:otp:" + EMAIL)).thenReturn(null);

        assertCode(ErrorCode.OTP_EXPIRED, () -> service.verifyOtp(EMAIL, "123456"));
    }

    @Test
    @DisplayName("Mã sai, còn lượt: OTP_INCORRECT")
    void verifyIncorrect() {
        when(values.get("password-reset:otp:" + EMAIL)).thenReturn("dung");
        when(values.get("password-reset:attempts:" + EMAIL)).thenReturn("0");
        when(otpHasher.hash(EMAIL, "000000")).thenReturn("sai");
        when(values.increment("password-reset:attempts:" + EMAIL)).thenReturn(1L);

        assertCode(ErrorCode.OTP_INCORRECT, () -> service.verifyOtp(EMAIL, "000000"));
    }

    @Test
    @DisplayName("Mã sai lần thứ 5: OTP_ATTEMPTS_EXCEEDED và huỷ mã")
    void verifyLastAttempt() {
        when(values.get("password-reset:otp:" + EMAIL)).thenReturn("dung");
        when(values.get("password-reset:attempts:" + EMAIL)).thenReturn("4");
        when(otpHasher.hash(EMAIL, "000000")).thenReturn("sai");
        when(values.increment("password-reset:attempts:" + EMAIL)).thenReturn(5L);

        assertCode(ErrorCode.OTP_ATTEMPTS_EXCEEDED, () -> service.verifyOtp(EMAIL, "000000"));
        verify(redisTemplate).delete("password-reset:otp:" + EMAIL);
    }

    @Test
    @DisplayName("Đã hết lượt từ trước: OTP_ATTEMPTS_EXCEEDED")
    void verifyAfterLimit() {
        when(values.get("password-reset:otp:" + EMAIL)).thenReturn("dung");
        when(values.get("password-reset:attempts:" + EMAIL)).thenReturn("5");

        assertCode(ErrorCode.OTP_ATTEMPTS_EXCEEDED, () -> service.verifyOtp(EMAIL, "123456"));
    }

    @Test
    @DisplayName("Reset token không còn: RESET_TOKEN_INVALID, không đổi mật khẩu")
    void resetWithInvalidToken() {
        when(values.getAndDelete("password-reset:token:het-han")).thenReturn(null);

        assertCode(ErrorCode.RESET_TOKEN_INVALID, () -> service.resetPassword("het-han", "MatKhauMoi#1"));
        verify(keycloakAdminClient, never()).setPassword(anyString(), anyString());
    }
}
