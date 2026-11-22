package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "warehouse_payroll_periods", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_payroll_period_company_start", columnNames = {"company_id", "period_start"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehousePayrollPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_rate_id", nullable = false)
    private CompanyWarehousePayrollRate payrollRate;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PayrollPeriodStatus status = PayrollPeriodStatus.DRAFT;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "locked_at", length = 19, columnDefinition = "varchar(19)")
    @Check(name = "warehouse_payroll_periods_locked_at_check",
            constraints = "locked_at IS NULL OR locked_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime lockedAt;

    @OneToMany(mappedBy = "payrollPeriod", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WarehousePayroll> workerPayrolls = new ArrayList<>();
}
