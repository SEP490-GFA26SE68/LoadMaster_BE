package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.account.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Một dòng bảng lương kho: một nhân viên kho trong một kỳ. */
@Entity
@Table(name = "warehouse_payrolls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_payroll_period_worker", columnNames = {"payroll_period_id", "worker_user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehousePayroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_period_id", nullable = false)
    private WarehousePayrollPeriod payrollPeriod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_user_id", nullable = false)
    private User worker;

    @Column(name = "base_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal baseSalaryVnd;

    /** Số kiện có xác nhận xếp còn hiệu lực của nhân viên trong tháng. */
    @Builder.Default
    @Column(name = "loaded_packages", nullable = false)
    private int loadedPackages = 0;

    @Column(name = "loaded_kg", nullable = false, precision = 15, scale = 3)
    private BigDecimal loadedKg;

    /** Tiền theo kg đã xếp: loadedKg × đơn giá, làm tròn đồng HALF_UP. */
    @Column(name = "loaded_amount_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal loadedAmountVnd;

    /** Có đạt chỉ tiêu KPI tháng này không (hệ thống tự tính từ tổng kg). */
    @Builder.Default
    @Column(name = "kpi_achieved", nullable = false)
    private boolean kpiAchieved = false;

    @Builder.Default
    @Column(name = "kpi_bonus_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal kpiBonusVnd = BigDecimal.ZERO;

    @Column(name = "total_salary_vnd", nullable = false, precision = 15, scale = 0)
    private BigDecimal totalSalaryVnd;

    @OneToMany(mappedBy = "workerPayroll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WarehousePayrollTripLine> trips = new ArrayList<>();
}
