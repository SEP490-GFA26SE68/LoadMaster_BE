package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.DispatcherPayroll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DispatcherPayrollRepository extends JpaRepository<DispatcherPayroll, Long> {

    Optional<DispatcherPayroll> findByPayrollPeriodIdAndDispatcherId(Long payrollPeriodId, Long dispatcherId);
}
