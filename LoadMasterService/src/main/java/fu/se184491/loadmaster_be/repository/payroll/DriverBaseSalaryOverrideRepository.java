package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.DriverBaseSalaryOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DriverBaseSalaryOverrideRepository extends JpaRepository<DriverBaseSalaryOverride, Long> {

    boolean existsByDriverIdAndEffectiveFrom(Long driverId, LocalDate effectiveFrom);

    /** Latest override on or before the given date — used during payroll calculation. */
    Optional<DriverBaseSalaryOverride> findFirstByDriverIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long driverId, LocalDate effectiveFrom);
}
