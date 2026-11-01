package fu.se184491.loadmaster_be.entity.company;

import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.entity.account.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "company_agreements",
        indexes = {
                @Index(name = "idx_company_agreements_company_id", columnList = "company_id"),
                @Index(name = "idx_company_agreements_status_deadline", columnList = "status, deadline_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", length = 50, nullable = false)
    @Builder.Default
    private AgreementType agreementType = AgreementType.TRIAL_TERMS;

    @Column(name = "agreement_code", length = 100, unique = true, nullable = false)
    private String agreementCode;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private AgreementStatus status = AgreementStatus.PENDING;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signer_name", length = 150)
    private String signerName;

    @Column(name = "signer_email", length = 150)
    private String signerEmail;

    @Column(name = "signer_phone", length = 50)
    private String signerPhone;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "company_code", length = 50)
    private String companyCode;

    @Column(name = "tax_code", length = 50)
    private String taxCode;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
