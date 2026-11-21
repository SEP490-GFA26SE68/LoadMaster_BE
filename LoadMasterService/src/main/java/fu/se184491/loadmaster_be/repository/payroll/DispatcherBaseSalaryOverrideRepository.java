package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.DispatcherBaseSalaryOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DispatcherBaseSalaryOverrideRepository extends JpaRepository<DispatcherBaseSalaryOverride, Long> {

    Optional<DispatcherBaseSalaryOverride> findFirstByDispatcherIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long dispatcherId, LocalDate effectiveFrom);
}
