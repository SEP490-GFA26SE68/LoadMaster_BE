package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripExceptionRepository extends JpaRepository<TripException, Long> {

    List<TripException> findByTripIdOrderByReportedAtAsc(Long tripId);

    Optional<TripException> findByIdAndTripId(Long id, Long tripId);
}
