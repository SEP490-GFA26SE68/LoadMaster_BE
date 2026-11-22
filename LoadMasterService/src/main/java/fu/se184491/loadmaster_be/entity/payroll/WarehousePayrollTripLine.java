package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Phần việc xếp của một nhân viên kho trên một chuyến trong kỳ: số kiện và kg đã xếp. */
@Entity
@Table(name = "warehouse_payroll_trip_lines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_payroll_trip", columnNames = {"warehouse_payroll_id", "trip_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehousePayrollTripLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_payroll_id", nullable = false)
    private WarehousePayroll workerPayroll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Ngày (giờ Việt Nam) của lần xác nhận xếp muộn nhất của nhân viên trên chuyến này trong kỳ. */
    @Column(name = "loaded_date", nullable = false)
    private LocalDate loadedDate;

    @Column(name = "loaded_packages", nullable = false)
    private int loadedPackages;

    @Column(name = "loaded_kg", nullable = false, precision = 15, scale = 3)
    private BigDecimal loadedKg;
}
