package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "company_payroll_rates", uniqueConstraints = {
        @UniqueConstraint(name = "uk_company_payroll_rate_effective", columnNames = {"company_id", "effective_from"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyPayrollRate {

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

    @Column(name = "rate_vnd_per_kg", nullable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKg;

    @Column(name = "rate_vnd_per_km", nullable = false, precision = 15, scale = 2)
    private BigDecimal rateVndPerKm;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
