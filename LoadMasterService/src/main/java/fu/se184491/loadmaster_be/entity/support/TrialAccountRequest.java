package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "trial_account_requests")
@Check(name = "chk_trial_account_requests_resolved_after_created", constraints = "resolved_at IS NULL OR resolved_at >= created_at")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrialAccountRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private ContactOfGuest contact;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "company_code", length = 50)
    private String companyCode;

    @Column(name = "tax_code", length = 50)
    private String taxCode;

    @Column(name = "company_address", columnDefinition = "TEXT")
    private String companyAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interested_plan_id")
    private SubscriptionPlan interestedPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private TrialRequestStatus status = TrialRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_precheck_status", length = 30)
    private AiPrecheckStatus aiPrecheckStatus;

    @Column(name = "ai_precheck_reason", columnDefinition = "TEXT")
    private String aiPrecheckReason;

    @Column(name = "handled_by_name", length = 100)
    private String handledByName;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "manager_note", columnDefinition = "TEXT")
    private String managerNote;

    @Column(name = "trial_days")
    private Integer trialDays;

    @Column(name = "trial_credits")
    private Integer trialCredits;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_reviewed_by_id")
    private User managerReviewedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_company_id")
    private Company createdCompany;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_account_requests_created_at_check", constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "resolved_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "trial_account_requests_resolved_at_check", constraints = "resolved_at IS NULL OR resolved_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime resolvedAt;

    @PrePersist
    @PreUpdate
    public void normalizeTimestamps() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (createdAt == null) {
            createdAt = now;
        } else {
            createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
        }
        if (resolvedAt != null) {
            resolvedAt = resolvedAt.truncatedTo(ChronoUnit.SECONDS);
            if (resolvedAt.isBefore(createdAt)) {
                resolvedAt = createdAt;
            }
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
