package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payroll_periods", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payroll_period_company_start", columnNames = {"company_id", "period_start"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_rate_id", nullable = false)
    private CompanyPayrollRate payrollRate;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PayrollPeriodStatus status = PayrollPeriodStatus.DRAFT;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @OneToMany(mappedBy = "payrollPeriod", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DriverPayroll> driverPayrolls = new ArrayList<>();
}
