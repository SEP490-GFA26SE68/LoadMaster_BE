package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Đơn giá lương nhân viên kho của công ty, áp dụng từ ngày 1 của một tháng: lương cứng + đơn giá theo kg kiện đã xếp lên xe
 * + thưởng KPI khi đạt chỉ tiêu kg. Tách khỏi đơn giá tài xế để chốt lương một vai trò không chặn vai trò kia.
 */
@Entity
@Table(name = "company_warehouse_payroll_rates", uniqueConstraints = {
        @UniqueConstraint(name = "uk_company_warehouse_payroll_rate_effective", columnNames = {"company_id", "effective_from"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyWarehousePayrollRate {

    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "base_salary_vnd", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseSalaryVnd;

    /** Tiền trả cho mỗi kg kiện nhân viên kho đã xếp lên xe trong tháng. */
    @Column(name = "rate_vnd_per_kg", nullable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKg;

    /** Chỉ tiêu KPI: tổng kg đã xếp trong tháng. Rỗng là công ty không dùng KPI. */
    @Column(name = "kpi_target_kg", precision = 15, scale = 3)
    private BigDecimal kpiTargetKg;

    /** Tiền thưởng khi đạt chỉ tiêu KPI; 0 khi công ty không dùng KPI. */
    @Builder.Default
    @Column(name = "kpi_bonus_vnd", nullable = false, precision = 15, scale = 2)
    private BigDecimal kpiBonusVnd = BigDecimal.ZERO;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "company_warehouse_payroll_rates_created_at_check",
            constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
