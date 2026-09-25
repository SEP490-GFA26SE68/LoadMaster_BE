package fu.se184491.loadmaster_be.repository.driver;

import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UnloadConfirmationRepository extends JpaRepository<UnloadConfirmation, Long> {

    Optional<UnloadConfirmation> findByCargoPackageId(Long packageId);

    boolean existsByCargoPackageId(Long packageId);

    List<UnloadConfirmation> findByDeliveryStopId(Long deliveryStopId);

    long countByDeliveryStopId(Long deliveryStopId);

    boolean existsByCargoPackageIdAndDeliveryStopId(Long packageId, Long deliveryStopId);
}
