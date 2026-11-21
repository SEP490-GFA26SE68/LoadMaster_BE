package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.account.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "dispatcher_payrolls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dispatcher_payroll_period_dispatcher", columnNames = {"payroll_period_id", "dispatcher_user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatcherPayroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_period_id", nullable = false)
    private DispatcherPayrollPeriod payrollPeriod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatcher_user_id", nullable = false)
    private User dispatcher;

    @Column(name = "base_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal baseSalaryVnd;

    @Column(name = "overtime_vnd_per_hour", nullable = false, precision = 15, scale = 2)
    private BigDecimal overtimeVndPerHour;

    @Column(name = "overtime_hours_per_day", nullable = false, precision = 5, scale = 2)
    private BigDecimal overtimeHoursPerDay;

    @Column(name = "overtime_days", nullable = false)
    private Integer overtimeDays;

    @Column(name = "overtime_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal overtimeAmountVnd;

    @Column(name = "kpi_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kpiAmountVnd;

    @Column(name = "total_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal totalSalaryVnd;
}
