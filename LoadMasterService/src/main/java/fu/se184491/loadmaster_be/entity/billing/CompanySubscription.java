package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "company_subscriptions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_company_subscription", columnNames = {"company_id"})
})
@Check(name = "chk_company_subscriptions_expires_after_started", constraints = "started_at IS NULL OR expires_at IS NULL OR expires_at >= started_at")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanySubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "started_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "company_subscriptions_started_at_check", constraints = "started_at IS NULL OR started_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime startedAt;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "expires_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "company_subscriptions_expires_at_check", constraints = "expires_at IS NULL OR expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(name = "auto_renew")
    private Boolean autoRenew = true;

    @PrePersist
    @PreUpdate
    public void normalizeTimestamps() {
        if (startedAt != null) {
            startedAt = startedAt.truncatedTo(ChronoUnit.SECONDS);
        }
        if (expiresAt != null) {
            expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
