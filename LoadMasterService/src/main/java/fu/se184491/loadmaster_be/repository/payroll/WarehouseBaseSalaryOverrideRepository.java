package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.WarehouseBaseSalaryOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WarehouseBaseSalaryOverrideRepository extends JpaRepository<WarehouseBaseSalaryOverride, Long> {

    Optional<WarehouseBaseSalaryOverride> findFirstByWorkerIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long workerId, LocalDate effectiveFrom);
}
