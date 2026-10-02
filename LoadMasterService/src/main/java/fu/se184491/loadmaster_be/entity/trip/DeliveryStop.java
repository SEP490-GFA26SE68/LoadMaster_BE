package fu.se184491.loadmaster_be.entity.trip;

import fu.se184491.loadmaster_be.entity.trip.Trip;


import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_stops", uniqueConstraints = @UniqueConstraint(
        name = "uk_delivery_stop_sequence", columnNames = {"trip_id", "stop_sequence"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryStop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "trip_id")
    private Trip trip;

    @Positive @Column(name = "stop_sequence")
    private Integer stopSequence;

    @Column(name = "stop_name", length = 150)
    private String stopName;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Builder.Default
    @ColumnDefault("'PENDING'")
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 20, nullable = false)
    private DeliveryStopStatus status = DeliveryStopStatus.PENDING;

    @Column(name = "planned_arrival")
    private LocalDateTime plannedArrival;

    @Column(name = "actual_arrival")
    private LocalDateTime actualArrival;
}
