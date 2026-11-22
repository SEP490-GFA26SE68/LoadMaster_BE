package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.WarehousePayroll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarehousePayrollRepository extends JpaRepository<WarehousePayroll, Long> {

    Optional<WarehousePayroll> findByPayrollPeriodIdAndWorkerId(Long payrollPeriodId, Long workerId);
}
