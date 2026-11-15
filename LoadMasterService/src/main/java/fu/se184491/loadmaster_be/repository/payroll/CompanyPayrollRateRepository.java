package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.CompanyPayrollRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompanyPayrollRateRepository extends JpaRepository<CompanyPayrollRate, Long> {

    List<CompanyPayrollRate> findByCompanyIdOrderByEffectiveFromDesc(Long companyId);

    Optional<CompanyPayrollRate> findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long companyId,
            LocalDate effectiveFrom
    );

    boolean existsByCompanyIdAndEffectiveFrom(Long companyId, LocalDate effectiveFrom);
}
