package fu.se184491.loadmaster_be.entity.order;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;


import fu.se184491.loadmaster_be.constant.order.OrderStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransportOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "delivery_stop_id")
    private DeliveryStop deliveryStop;

    @Column(name = "order_code", length = 50, unique = true)
    private String orderCode;

    @Builder.Default @PositiveOrZero
    @Column(name = "total_weight_kg", precision = 10, scale = 2)
    private BigDecimal totalWeightKg = BigDecimal.ZERO;

    @Builder.Default @PositiveOrZero
    @Column(name = "total_volume_cbm", precision = 10, scale = 4)
    private BigDecimal totalVolumeCbm = BigDecimal.ZERO;

    @Column(name = "time_window_start")
    private LocalDateTime timeWindowStart;

    @Column(name = "time_window_end")
    private LocalDateTime timeWindowEnd;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private OrderStatus status;
}
