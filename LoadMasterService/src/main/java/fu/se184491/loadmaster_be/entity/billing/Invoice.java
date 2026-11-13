package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoices", uniqueConstraints = {
        // Một thanh toán thành công có đúng một hoá đơn, kể cả khi cổng thanh toán gửi lại callback
        @UniqueConstraint(name = "uk_invoices_payment_transaction", columnNames = {"payment_transaction_id"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_subscription_id")
    private CompanySubscription companySubscription;

    /** Thanh toán đã tạo ra hoá đơn; {@code null} với hoá đơn của hợp đồng ký tay. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id", foreignKey = @ForeignKey(name = "fk_invoices_payment_transaction"))
    private PaymentTransaction paymentTransaction;

    /** Gói đã mua, chụp lúc thanh toán (gói của công ty có thể đổi sau); {@code null} với hoá đơn nạp credit. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", foreignKey = @ForeignKey(name = "fk_invoices_plan"))
    private SubscriptionPlan plan;

    /** {@code order_type} của thanh toán: SUBSCRIPTION, SUBSCRIPTION_YEARLY, SUBSCRIPTION_RENEWAL, CREDIT_TOPUP, GUEST_REGISTRATION. */
    @Column(name = "item_type", length = 50)
    private String itemType;

    /** Số credit đã mua, chỉ có ở hoá đơn nạp credit. */
    @Column(name = "credit_quantity")
    private Integer creditQuantity;

    @Column(name = "invoice_number", unique = true)
    private String invoiceNumber;

    @Column(name = "billing_period", length = 50)
    private String billingPeriod;

    @Column(name = "subtotal_amount", precision = 15, scale = 2)
    private BigDecimal subtotalAmount;

    @Builder.Default
    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private InvoiceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
