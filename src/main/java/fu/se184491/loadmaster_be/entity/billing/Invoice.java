package fu.se184491.loadmaster_be.entity.billing;

import fu.se184491.loadmaster_be.entity.billing.Subscription;


import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @Column(name = "invoice_number", unique = true)
    private String invoiceNumber;

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
}
