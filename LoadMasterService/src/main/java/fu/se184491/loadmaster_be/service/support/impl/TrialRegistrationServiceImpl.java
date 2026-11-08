package fu.se184491.loadmaster_be.service.support.impl;

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
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.repository.support.TrialRegistrationSessionRepository;
import fu.se184491.loadmaster_be.service.security.OtpHasher;
import fu.se184491.loadmaster_be.service.support.TrialPreValidationService;
import fu.se184491.loadmaster_be.service.support.TrialRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrialRegistrationServiceImpl implements TrialRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int OTP_RESEND_COOLDOWN_SECONDS = 60;
    private static final int SESSION_EXPIRY_HOURS = 24;

    private final TrialRegistrationSessionRepository sessionRepository;
    private final TrialAccountRequestRepository trialAccountRequestRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final OtpHasher otpHasher;
    private final BrevoClient brevoClient;
    private final TrialPreValidationService trialPreValidationService;

    @Override
    @Transactional
    public GuestTrialInitResponse initTrialRegistration(GuestTrialInitRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String taxCode = request.getTaxCode().trim();

        // 1. Kiểm tra thông tin sơ bộ trước khi sinh OTP (Bắt buộc hợp lệ mới cho gửi OTP)
        trialPreValidationService.checkEligibility(taxCode, email);

        // 2. Gói dùng thử bắt buộc là gói Basic
        SubscriptionPlan basicPlan = subscriptionPlanRepository.findByTierAndActiveTrue(SubscriptionTier.BASIC)
                .orElseGet(() -> subscriptionPlanRepository.findByActiveTrue().stream().findFirst().orElse(null));

        Instant now = Instant.now();

        // 3. Sinh OTP 6 số ngẫu nhiên
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hashedOtp = otpHasher.hash(email, otp);
        Instant otpExpiresAt = now.plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES);
        Instant otpResendAllowedAt = now.plus(OTP_RESEND_COOLDOWN_SECONDS, ChronoUnit.SECONDS);

        // 4. Tạo session lưu trữ tạm thời
        String token = UUID.randomUUID().toString();
        TrialRegistrationSession session = TrialRegistrationSession.builder()
                .token(token)
                .email(email)
                .fullName(request.getFullName().trim())
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                .companyName(request.getCompanyName().trim())
                .taxCode(taxCode)
                .companyAddress(request.getCompanyAddress() != null ? request.getCompanyAddress().trim() : null)
                .interestedPlan(basicPlan)
                .note(request.getNote())
                .otpHash(hashedOtp)
                .otpExpiresAt(otpExpiresAt)
                .otpResendAllowedAt(otpResendAllowedAt)
                .verified(false)
                .createdAt(now)
                .expiresAt(now.plus(SESSION_EXPIRY_HOURS, ChronoUnit.HOURS))
                .build();

        sessionRepository.save(session);

        // 5. Gửi email chứa mã OTP
        sendOtpEmail(email, request.getFullName(), otp);

        return GuestTrialInitResponse.builder()
                .token(token)
                .email(email)
                .maskedEmail(maskEmail(email))
                .otpExpiresAt(otpExpiresAt)
                .otpResendAllowedAt(otpResendAllowedAt)
                .message("Mã xác thực OTP gồm 6 chữ số đã được gửi đến email của bạn.")
                .build();
    }

    @Override
    @Transactional
    public GuestTrialVerifyResponse verifyOtp(GuestTrialVerifyOtpRequest request) {
        TrialRegistrationSession session = sessionRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND, "Phiên đăng ký không hợp lệ hoặc đã hết hạn"));

        Instant now = Instant.now();
        if (session.getExpiresAt().isBefore(now)) {
            throw new AppException(ErrorCode.REGISTRATION_SESSION_EXPIRED, "Phiên đăng ký đã hết hạn. Vui lòng thực hiện lại.");
        }

        if (Boolean.TRUE.equals(session.getVerified())) {
            throw new AppException(ErrorCode.REGISTRATION_INVALID_STATUS, "Phiên đăng ký này đã được xác thực thành công trước đó.");
        }

        if (session.getOtpExpiresAt() == null || session.getOtpExpiresAt().isBefore(now)) {
            throw new AppException(ErrorCode.OTP_EXPIRED, "Mã OTP đã hết hiệu lực. Vui lòng yêu cầu gửi lại mã mới.");
        }

        // Kiểm tra mã OTP khớp
        String inputHashed = otpHasher.hash(session.getEmail(), request.getOtp().trim());
        if (!inputHashed.equals(session.getOtpHash())) {
            throw new AppException(ErrorCode.OTP_INCORRECT, "Mã OTP không chính xác. Vui lòng kiểm tra lại.");
        }

        // Đánh dấu session đã verify
        session.setVerified(true);
        sessionRepository.save(session);

        // Chuyển session thành TrialAccountRequest chính thức (đã qua thẩm định sơ bộ đạt)
        TrialAccountRequest trialRequest = TrialAccountRequest.builder()
                .fullName(session.getFullName())
                .email(session.getEmail())
                .phoneNumber(session.getPhoneNumber())
                .companyName(session.getCompanyName())
                .taxCode(session.getTaxCode())
                .companyAddress(session.getCompanyAddress())
                .interestedPlan(session.getInterestedPlan())
                .note(session.getNote())
                .status(TrialRequestStatus.PENDING)
                .aiPrecheckStatus(AiPrecheckStatus.PASSED)
                .aiPrecheckReason("Thông tin hợp lệ, vượt qua bước kiểm tra tự động")
                .handledByName(null)
                .createdAt(now)
                .build();

        TrialAccountRequest savedRequest = trialAccountRequestRepository.save(trialRequest);

        return GuestTrialVerifyResponse.builder()
                .requestId(savedRequest.getId())
                .status(savedRequest.getStatus())
                .aiPrecheckStatus(savedRequest.getAiPrecheckStatus())
                .aiPrecheckReason(savedRequest.getAiPrecheckReason())
                .message("Xác thực email thành công! Đơn đăng ký trải nghiệm của bạn đã được chuyển đến System Manager để thẩm định và cấp tài khoản.")
                .build();
    }

    @Override
    @Transactional
    public GuestTrialInitResponse resendOtp(GuestTrialResendOtpRequest request) {
        TrialRegistrationSession session = sessionRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND, "Phiên đăng ký không hợp lệ"));

        Instant now = Instant.now();
        if (session.getOtpResendAllowedAt() != null && now.isBefore(session.getOtpResendAllowedAt())) {
            long waitSeconds = ChronoUnit.SECONDS.between(now, session.getOtpResendAllowedAt());
            throw new AppException(ErrorCode.OTP_RESEND_COOLDOWN, "Vui lòng đợi " + Math.max(1, waitSeconds) + " giây trước khi gửi lại OTP.");
        }

        // Tạo OTP mới
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hashedOtp = otpHasher.hash(session.getEmail(), otp);
        Instant otpExpiresAt = now.plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES);
        Instant otpResendAllowedAt = now.plus(OTP_RESEND_COOLDOWN_SECONDS, ChronoUnit.SECONDS);

        session.setOtpHash(hashedOtp);
        session.setOtpExpiresAt(otpExpiresAt);
        session.setOtpResendAllowedAt(otpResendAllowedAt);
        sessionRepository.save(session);

        sendOtpEmail(session.getEmail(), session.getFullName(), otp);

        return GuestTrialInitResponse.builder()
                .token(session.getToken())
                .email(session.getEmail())
                .maskedEmail(maskEmail(session.getEmail()))
                .otpExpiresAt(otpExpiresAt)
                .otpResendAllowedAt(otpResendAllowedAt)
                .message("Mã OTP mới đã được gửi lại vào email của bạn.")
                .build();
    }

    private void sendOtpEmail(String email, String fullName, String otp) {
        String subject = "LoadMaster - Mã OTP xác thực đăng ký trải nghiệm";
        String content = String.format("""
                Kính gửi Quý khách %s,
                
                Mã xác thực (OTP) cho yêu cầu đăng ký tài khoản trải nghiệm LoadMaster của Quý khách là:
                
                👉 %s 👈
                
                Mã xác thực có hiệu lực trong vòng 10 phút. Vui lòng không chia sẻ mã này cho bất kỳ ai.
                
                Trân trọng,
                Đội ngũ LoadMaster
                """, fullName, otp);

        try {
            brevoClient.sendEmail(email, subject, content);
        } catch (Exception ex) {
            log.error("Failed to send OTP email to {}: {}", email, ex.getMessage());
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int atIdx = email.indexOf('@');
        String username = email.substring(0, atIdx);
        String domain = email.substring(atIdx);
        if (username.length() <= 2) {
            return username.charAt(0) + "***" + domain;
        }
        return username.substring(0, 2) + "***" + domain;
    }
}
