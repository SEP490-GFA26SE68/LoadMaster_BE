package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CargoPackageRepository extends JpaRepository<CargoPackage, Long> {

    Optional<CargoPackage> findByTrackingBarcode(String trackingBarcode);

    List<CargoPackage> findByOrderDeliveryStopId(Long stopId);
}
