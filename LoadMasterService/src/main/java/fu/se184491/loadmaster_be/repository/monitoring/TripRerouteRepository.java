package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.entity.monitoring.TripReroute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripRerouteRepository extends JpaRepository<TripReroute, Long> {

    Optional<TripReroute> findFirstByTripIdOrderByRequestedAtDesc(Long tripId);

    Optional<TripReroute> findFirstByTripIdAndConfirmedAtIsNotNullOrderByConfirmedAtDesc(Long tripId);
}
