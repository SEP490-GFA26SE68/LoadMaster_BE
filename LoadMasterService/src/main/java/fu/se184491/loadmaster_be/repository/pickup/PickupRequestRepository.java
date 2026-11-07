package fu.se184491.loadmaster_be.repository.pickup;

import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PickupRequestRepository extends JpaRepository<PickupRequest, Long> {

    List<PickupRequest> findByTripIdOrderByIdAsc(Long tripId);

    Optional<PickupRequest> findByIdAndTripId(Long id, Long tripId);

    List<PickupRequest> findByTripIdAndStatusIn(Long tripId, Collection<PickupRequestStatus> statuses);

    List<PickupRequest> findByPickupStopId(Long stopId);

    List<PickupRequest> findByDeliveryStopIdAndStatusIn(Long stopId, Collection<PickupRequestStatus> statuses);
}
