package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "trial_registration_sessions")
@Check(
        name = "chk_trial_reg_timestamps_after_created",
        constraints = "expires_at >= created_at AND (otp_expires_at IS NULL OR otp_expires_at >= created_at) AND (otp_resend_allowed_at IS NULL OR otp_resend_allowed_at >= created_at)"
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrialRegistrationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 100)
    private String token;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "tax_code", nullable = false, length = 50)
    private String taxCode;

    @Column(name = "company_address", columnDefinition = "TEXT")
    private String companyAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interested_plan_id")
    private SubscriptionPlan interestedPlan;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "otp_hash", length = 255)
    private String otpHash;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "otp_expires_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_registration_sessions_otp_expires_at_check", constraints = "otp_expires_at IS NULL OR otp_expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime otpExpiresAt;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "otp_resend_allowed_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_registration_sessions_otp_resend_allowed_at_check", constraints = "otp_resend_allowed_at IS NULL OR otp_resend_allowed_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime otpResendAllowedAt;

    @Column(name = "verified")
    @Builder.Default
    private Boolean verified = false;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_registration_sessions_created_at_check", constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "expires_at", nullable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_registration_sessions_expires_at_check", constraints = "expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime expiresAt;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (createdAt == null) {
            createdAt = now;
        } else {
            createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
        }
        if (expiresAt == null) {
            expiresAt = createdAt.plus(24, ChronoUnit.HOURS);
        } else {
            expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
            if (expiresAt.isBefore(createdAt)) {
                expiresAt = createdAt;
            }
        }
        if (otpExpiresAt != null) {
            otpExpiresAt = otpExpiresAt.truncatedTo(ChronoUnit.SECONDS);
            if (otpExpiresAt.isBefore(createdAt)) {
                otpExpiresAt = createdAt;
            }
        }
        if (otpResendAllowedAt != null) {
            otpResendAllowedAt = otpResendAllowedAt.truncatedTo(ChronoUnit.SECONDS);
            if (otpResendAllowedAt.isBefore(createdAt)) {
                otpResendAllowedAt = createdAt;
            }
        }
        if (verified == null) {
            verified = false;
        }
    }

    @Converter
    public static class LocalDateTimeSecondsConverter implements AttributeConverter<LocalDateTime, String> {
        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String convertToDatabaseColumn(LocalDateTime attribute) {
            if (attribute == null) {
                return null;
            }
            return attribute.truncatedTo(ChronoUnit.SECONDS).format(FORMATTER);
        }

        @Override
        public LocalDateTime convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            String trimmed = dbData.trim();
            if (trimmed.length() > 19) {
                trimmed = trimmed.substring(0, 19);
            }
            return LocalDateTime.parse(trimmed.replace('T', ' '), FORMATTER);
        }
    }
}

