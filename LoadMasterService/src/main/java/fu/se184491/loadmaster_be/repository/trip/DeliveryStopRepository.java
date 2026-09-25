package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryStopRepository extends JpaRepository<DeliveryStop, Long> {

    List<DeliveryStop> findByTripIdOrderByStopSequenceAsc(Long tripId);

    Optional<DeliveryStop> findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(Long tripId, DeliveryStopStatus status);

    long countByTripId(Long tripId);
}
