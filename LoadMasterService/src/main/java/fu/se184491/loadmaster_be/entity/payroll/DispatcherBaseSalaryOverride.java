package fu.se184491.loadmaster_be.entity.payroll;

import fu.se184491.loadmaster_be.entity.account.User;
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

@Entity
@Table(name = "dispatcher_base_salary_overrides", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dispatcher_base_salary_effective", columnNames = {"dispatcher_user_id", "effective_from"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatcherBaseSalaryOverride {

    private static final ZoneId VIETNAM_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatcher_user_id", nullable = false)
    private User dispatcher;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "base_salary_vnd", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseSalaryVnd;

    @Convert(converter = SupportTicket.LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false, length = 19, columnDefinition = "varchar(19)")
    @Check(name = "dispatcher_base_salary_overrides_created_at_check",
            constraints = "created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(VIETNAM_TIME_ZONE).truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
