package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.DriverPayroll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DriverPayrollRepository extends JpaRepository<DriverPayroll, Long> {

    List<DriverPayroll> findByPayrollPeriodId(Long payrollPeriodId);

    Optional<DriverPayroll> findByPayrollPeriodIdAndDriverId(Long payrollPeriodId, Long driverId);
}
