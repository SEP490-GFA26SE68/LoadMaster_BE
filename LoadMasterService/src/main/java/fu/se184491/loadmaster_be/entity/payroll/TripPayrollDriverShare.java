package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "trip_payroll_driver_shares", uniqueConstraints = {
        @UniqueConstraint(name = "uk_trip_payroll_driver", columnNames = {"trip_id", "driver_user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPayrollDriverShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_user_id", nullable = false)
    private User driver;

    @Column(name = "share_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercent;
}
