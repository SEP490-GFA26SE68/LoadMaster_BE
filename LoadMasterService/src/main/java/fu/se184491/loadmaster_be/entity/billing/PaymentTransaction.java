package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "payment_transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gateway_transaction_id", columnNames = {"gateway_transaction_id"})
})
@Check(name = "chk_payment_transactions_updated_after_created", constraints = "updated_at >= created_at")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", length = 20, nullable = false, updatable = false)
    private PaymentGateway gateway;

    @Column(name = "gateway_transaction_id", length = 100, nullable = false, updatable = false)
    private String gatewayTransactionId;

    @Column(name = "amount_vnd", precision = 15, scale = 2, nullable = false, updatable = false)
    private BigDecimal amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "order_type", length = 50, updatable = false)
    private String orderType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Builder.Default
    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "payment_transactions_created_at_check", constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Builder.Default
    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "updated_at", nullable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "payment_transactions_updated_at_check", constraints = "updated_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (this.createdAt == null) {
            this.createdAt = now;
        } else {
            this.createdAt = this.createdAt.truncatedTo(ChronoUnit.SECONDS);
        }
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (this.createdAt != null && now.isBefore(this.createdAt)) {
            this.updatedAt = this.createdAt;
        } else {
            this.updatedAt = now;
        }
    }
}
