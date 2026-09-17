package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.PaymentChannel;
import fu.se184491.loadmaster_be.constant.PaymentGateway;
import fu.se184491.loadmaster_be.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(name = "transaction_code", length = 100, unique = true)
    private String transactionCode;

    @Column(name = "gateway_trans_id", length = 100)
    private String gatewayTransId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway")
    private PaymentGateway paymentGateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel")
    private PaymentChannel paymentChannel;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "signature", length = 500)
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PaymentStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
