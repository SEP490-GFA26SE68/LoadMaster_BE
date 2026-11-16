package fu.se184491.loadmaster_be.repository.payroll;

import fu.se184491.loadmaster_be.entity.payroll.TripPayrollDriverShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TripPayrollDriverShareRepository extends JpaRepository<TripPayrollDriverShare, Long> {

    List<TripPayrollDriverShare> findByTripId(Long tripId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TripPayrollDriverShare s WHERE s.trip.id = :tripId")
    void deleteByTripId(@Param("tripId") Long tripId);
}
