package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "driver_payroll_trip_lines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_driver_payroll_trip", columnNames = {"driver_payroll_id", "trip_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverPayrollTripLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_payroll_id", nullable = false)
    private DriverPayroll driverPayroll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "load_plan_id", nullable = false)
    private Long loadPlanId;

    @Column(name = "route_plan_id", nullable = false)
    private Long routePlanId;

    @Column(name = "plan_kg", nullable = false, precision = 15, scale = 3)
    private BigDecimal planKg;

    @Column(name = "plan_km", nullable = false, precision = 15, scale = 3)
    private BigDecimal planKm;

    @Column(name = "share_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercent;

    @Column(name = "allocated_kg", nullable = false, precision = 15, scale = 3)
    private BigDecimal allocatedKg;

    @Column(name = "allocated_km", nullable = false, precision = 15, scale = 3)
    private BigDecimal allocatedKm;

    @Column(name = "rate_vnd_per_kg", nullable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKg;

    @Column(name = "rate_vnd_per_km", nullable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKm;

    @Column(name = "kg_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kgAmountVnd;

    @Column(name = "km_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kmAmountVnd;

    @Column(name = "total_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal totalAmountVnd;
}
