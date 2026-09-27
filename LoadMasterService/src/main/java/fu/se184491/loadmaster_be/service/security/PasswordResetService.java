package fu.se184491.loadmaster_be.service.security;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

//    private static final Duration OTP_TTL = Duration.ofMinutes(3);
//    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
//    private static final Duration REQUEST_RATE_TTL = Duration.ofMinutes(15);
//    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(5);

    private static final Duration OTP_TTL = Duration.ofSeconds(30);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(10);
    private static final Duration REQUEST_RATE_TTL = Duration.ofMinutes(2);
    private static final Duration RESET_TOKEN_TTL = Duration.ofSeconds(30);

    private final BrevoClient brevoClient;
    private final OtpHasher otpHasher;

    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int MAX_REQUESTS = 3;

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    private final SecureRandom secureRandom = new SecureRandom();

    public void requestOtp(String rawEmail){

        String email = normalizeEmail(rawEmail);

        // Tránh lộ email có tồn tại hay không:
        // Controller vẫn nên trả cùng một message trong mọi trường hợp.
        if(!userRepository.existsByEmail(email)){
            return ;
        }

        String cooldownKey = cooldownKey(email);
        String rateKey = requestRateKey(email);

        // 1. Không cho resend liên tục trong 60 giây
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new IllegalStateException(
                    "Vui lòng chờ trước khi yêu cầu mã OTP mới"
            );
        }

        // 2. Rate limit: tối đa 3 request / 15 phút / email
        Long requestCount =
                redisTemplate.opsForValue().increment(rateKey);

        if (requestCount != null && requestCount == 1) {
            redisTemplate.expire(
                    rateKey,
                    REQUEST_RATE_TTL
            );
        }

        if (requestCount != null && requestCount > MAX_REQUESTS) {
            throw new IllegalStateException(
                    "Bạn đã yêu cầu OTP quá nhiều lần. Vui lòng thử lại sau"
            );
        }

        // 3. Generate OTP 6 số
        String otp = String.format(
                "%06d",
                secureRandom.nextInt(1_000_000)
        );

        // Bước tiếp theo sẽ hash/HMAC OTP trước khi lưu.
        // Hiện tại giữ plaintext để test flow Redis trước.
        String otpHash =
                otpHasher.hash(email, otp);

        redisTemplate.opsForValue().set(
                otpKey(email),
                otpHash,
                OTP_TTL
        );

        brevoClient.sendPasswordResetOtp(
                email,
                otp
        );

        // OTP mới => reset số lần nhập sai
        redisTemplate.opsForValue().set(
                attemptsKey(email),
                "0",
                OTP_TTL
        );

        // Cooldown gửi lại
        redisTemplate.opsForValue().set(
                cooldownKey,
                "1",
                COOLDOWN_TTL
        );

        return;

    }

    public String verifyOtp(String rawEmail, String otp){
        String email = normalizeEmail(rawEmail);

        String otpKey = otpKey(email);
        String attemptsKey = attemptsKey(email);

        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if (storedOtp == null) {
            throw new IllegalStateException(
                    "OTP đã hết hạn hoặc không hợp lệ"
            );
        }
        String attemptsValue =
                redisTemplate.opsForValue().get(attemptsKey);

        int attempts = attemptsValue == null
                ? 0
                : Integer.parseInt(attemptsValue);


        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            invalidateOtp(email);

            throw new IllegalStateException(
                    "Bạn đã nhập sai OTP quá nhiều lần"
            );
        }

        //otp sai
        String incomingHash =
                otpHasher.hash(email, otp);

        if (!secureEquals(storedOtp, incomingHash)) {

            Long newAttempts =
                    redisTemplate.opsForValue()
                            .increment(attemptsKey);

            if (newAttempts != null
                    && newAttempts >= MAX_VERIFY_ATTEMPTS) {

                invalidateOtp(email);

                throw new IllegalStateException(
                        "Bạn đã nhập sai OTP quá nhiều lần"
                );
            }

            throw new IllegalStateException(
                    "OTP không chính xác"
            );
        }

        // OTP đúng => OTP chỉ dùng một lần
        invalidateOtp(email);

        String resetToken = generateResetToken();

        redisTemplate.opsForValue().set(
                resetTokenKey(resetToken),
                email,
                RESET_TOKEN_TTL
        );

        return resetToken;

    }

    public void resetPassword(
            String resetToken,
            String newPassword
    ) {

        String email =
                redisTemplate.opsForValue()
                        .getAndDelete(
                                resetTokenKey(resetToken)
                        );

        if (email == null) {
            throw new IllegalStateException(
                    "Reset token đã hết hạn hoặc không hợp lệ"
            );
        }

        User user = userRepository
                .findByEmail(email)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Không tìm thấy người dùng"
                        )
                );

        try {
            keycloakAdminClient.setPassword(
                    user.getKeycloakId(),
                    newPassword
            );
        } catch (Exception ex) {


//             Nếu Keycloak reset password thất bại,
//             restore reset token để user có thể thử lại
//             trong thời gian ngắn.

            redisTemplate.opsForValue().set(
                    resetTokenKey(resetToken),
                    email,
                    RESET_TOKEN_TTL
            );

            throw ex;
        }
    }

    private void invalidateOtp(String email) {
        redisTemplate.delete(otpKey(email));
        redisTemplate.delete(attemptsKey(email));
    }

    private String generateResetToken() {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String normalizeEmail(String email) {
        return email
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String otpKey(String email) {
        return "password-reset:otp:" + email;
    }

    private String attemptsKey(String email) {
        return "password-reset:attempts:" + email;
    }

    private String cooldownKey(String email) {
        return "password-reset:cooldown:" + email;
    }

    private String requestRateKey(String email) {
        return "password-reset:request-rate:" + email;
    }

    private String resetTokenKey(String token) {
        return "password-reset:token:" + token;
    }

    private boolean secureEquals(
            String first,
            String second
    ) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8)
        );
    }

}