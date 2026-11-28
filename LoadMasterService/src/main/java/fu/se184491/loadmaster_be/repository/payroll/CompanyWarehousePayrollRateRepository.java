package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.CompanyWarehousePayrollRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompanyWarehousePayrollRateRepository extends JpaRepository<CompanyWarehousePayrollRate, Long> {

    List<CompanyWarehousePayrollRate> findByCompanyIdOrderByEffectiveFromDesc(Long companyId);

    Optional<CompanyWarehousePayrollRate> findFirstByCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long companyId,
            LocalDate effectiveFrom
    );

    boolean existsByCompanyId(Long companyId);

    Optional<CompanyWarehousePayrollRate> findByCompanyIdAndEffectiveFrom(Long companyId, LocalDate effectiveFrom);
}
