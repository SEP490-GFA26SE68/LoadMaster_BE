package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "trial_registration_sessions")
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

    @Column(name = "otp_expires_at")
    private Instant otpExpiresAt;

    @Column(name = "otp_resend_allowed_at")
    private Instant otpResendAllowedAt;

    @Column(name = "verified")
    @Builder.Default
    private Boolean verified = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (verified == null) {
            verified = false;
        }
    }
}
