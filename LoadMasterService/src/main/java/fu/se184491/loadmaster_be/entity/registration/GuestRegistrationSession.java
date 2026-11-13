package fu.se184491.loadmaster_be.entity.registration;

import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "guest_registration_sessions",
        indexes = {
                @Index(name = "idx_guest_reg_token", columnList = "token"),
                @Index(name = "idx_guest_reg_email", columnList = "email"),
                @Index(name = "idx_guest_reg_payment_ref", columnList = "payment_ref")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_guest_reg_token", columnNames = "token")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestRegistrationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", length = 100, nullable = false, unique = true)
    private String token;

    @Column(name = "email", length = 150, nullable = false)
    private String email;

    @Column(name = "full_name", length = 150, nullable = false)
    private String fullName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "company_name", length = 255, nullable = false)
    private String companyName;

    @Column(name = "company_code", length = 50, nullable = false)
    private String companyCode;

    @Column(name = "tax_code", length = 50, nullable = false)
    private String taxCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", length = 20)
    private BillingCycle billingCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private RegistrationStatus status = RegistrationStatus.PENDING_EMAIL_VERIFY;

    @Column(name = "otp_hash", length = 255)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    @Column(name = "otp_resend_allowed_at")
    private LocalDateTime otpResendAllowedAt;

    /** Số lần nhập sai mã OTP hiện tại; đủ giới hạn thì mã bị huỷ. */
    @Column(name = "otp_failed_attempts")
    private Integer otpFailedAttempts;

    @Column(name = "policy_accepted_at")
    private LocalDateTime policyAcceptedAt;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "payment_ref", length = 100)
    private String paymentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway", length = 20)
    private PaymentGateway paymentGateway;

    @Column(name = "amount_vnd", precision = 15, scale = 2)
    private BigDecimal amountVnd;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (expiresAt == null) {
            // 3 hours timeout as per requirement
            expiresAt = createdAt.plusHours(3);
        }
    }
}

