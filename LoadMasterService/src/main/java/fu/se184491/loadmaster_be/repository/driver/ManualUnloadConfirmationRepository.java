package fu.se184491.loadmaster_be.repository.driver;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManualUnloadConfirmationRepository extends JpaRepository<ManualUnloadConfirmation, Long> {

    List<ManualUnloadConfirmation> findByDeliveryStopIdOrderByRequestedAtAsc(Long deliveryStopId);

    List<ManualUnloadConfirmation> findByTripIdAndStatusOrderByRequestedAtAsc(Long tripId, ManualConfirmStatus status);

    long countByDeliveryStopIdAndStatus(Long deliveryStopId, ManualConfirmStatus status);

    boolean existsByCargoPackageIdAndStatus(Long packageId, ManualConfirmStatus status);
}
