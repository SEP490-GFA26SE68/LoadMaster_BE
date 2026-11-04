package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "credit_transactions", indexes = {
        @Index(name = "idx_credit_account_id", columnList = "credit_account_id"),
        @Index(name = "idx_credit_reference", columnList = "reference")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_account_id", nullable = false)
    private CreditAccount creditAccount;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private CreditTransactionType type;

    @Column(name = "reference", length = 100)
    private String reference;

    @Builder.Default
    @Column(name = "refunded", nullable = false)
    private Boolean refunded = false;

    @Builder.Default
    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "credit_transactions_created_at_check", constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        } else {
            createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
