package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.CompanyDispatcherPayrollRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompanyDispatcherPayrollRateRepository extends JpaRepository<CompanyDispatcherPayrollRate, Long> {

    List<CompanyDispatcherPayrollRate> findByCompanyIdOrderByEffectiveFromDesc(Long companyId);

    Optional<CompanyDispatcherPayrollRate> findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long companyId,
            LocalDate effectiveFrom
    );

    boolean existsByCompanyId(Long companyId);

    Optional<CompanyDispatcherPayrollRate> findByCompanyIdAndEffectiveFrom(Long companyId, LocalDate effectiveFrom);
}
