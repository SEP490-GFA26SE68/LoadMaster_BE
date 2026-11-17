package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.entity.payroll.PayrollPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayrollPeriodRepository extends JpaRepository<PayrollPeriod, Long> {

    Optional<PayrollPeriod> findByCompanyIdAndPeriodStart(Long companyId, LocalDate periodStart);

    boolean existsByCompanyIdAndPayrollRateIdAndStatus(Long companyId, Long payrollRateId, PayrollPeriodStatus status);

    List<PayrollPeriod> findByCompanyIdAndPayrollRateIdAndStatusOrderByPeriodStartAsc(
            Long companyId, Long payrollRateId, PayrollPeriodStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select period from PayrollPeriod period where period.company.id = :companyId and period.periodStart = :periodStart")
    Optional<PayrollPeriod> findByCompanyIdAndPeriodStartForUpdate(
            @Param("companyId") Long companyId,
            @Param("periodStart") LocalDate periodStart
    );
}
