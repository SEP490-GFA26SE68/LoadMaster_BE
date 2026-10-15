package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GpsTrackingRepository extends JpaRepository<GpsTracking, Long> {

    Optional<GpsTracking> findFirstByTripIdOrderByRecordedAtDesc(Long tripId);

    List<GpsTracking> findByTripIdOrderByRecordedAtAsc(Long tripId);
}
