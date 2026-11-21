package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.account.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "driver_payrolls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_driver_payroll_period_driver", columnNames = {"payroll_period_id", "driver_user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverPayroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_period_id", nullable = false)
    private PayrollPeriod payrollPeriod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_user_id", nullable = false)
    private User driver;

    @Column(name = "base_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal baseSalaryVnd;

    @Column(name = "total_kg", nullable = false, precision = 15, scale = 3)
    private BigDecimal totalKg;

    @Column(name = "kg_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kgAmountVnd;

    @Column(name = "handling_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal handlingAmountVnd;

    /** Tài xế có đạt chỉ tiêu KPI tháng này không (hệ thống tự tính từ tổng kg, không ai đánh dấu tay). */
    @Builder.Default
    @Column(name = "kpi_achieved", nullable = false)
    private boolean kpiAchieved = false;

    /** Tiền thưởng KPI đã cộng vào lương: bằng mức thưởng của công ty khi đạt, 0 khi không đạt. */
    @Builder.Default
    @Column(name = "kpi_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kpiAmountVnd = BigDecimal.ZERO;

    @Column(name = "total_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal totalSalaryVnd;

    @OneToMany(mappedBy = "driverPayroll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DriverPayrollTripLine> trips = new ArrayList<>();
}
