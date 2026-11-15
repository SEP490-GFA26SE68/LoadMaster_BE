package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.TripPayrollDriverShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPayrollDriverShareRepository extends JpaRepository<TripPayrollDriverShare, Long> {

    List<TripPayrollDriverShare> findByTripId(Long tripId);

    void deleteByTripId(Long tripId);
}
