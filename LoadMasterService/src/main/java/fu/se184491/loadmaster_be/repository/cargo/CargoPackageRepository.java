package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CargoPackageRepository extends JpaRepository<CargoPackage, Long> {

    Optional<CargoPackage> findByTrackingBarcode(String trackingBarcode);

    Optional<CargoPackage> findByQrToken(String qrToken);

    boolean existsByQrToken(String qrToken);

    Optional<CargoPackage> findByPackageCode(String packageCode);

    boolean existsByOrderCompanyIdAndPackageCode(Long companyId, String packageCode);

    List<CargoPackage> findByOrderDeliveryStopId(Long stopId);

    List<CargoPackage> findAllByIdInAndOrderCompanyId(List<Long> ids, Long companyId);

    List<CargoPackage> findByOrderDeliveryStopTripIdOrderByIdAsc(Long tripId);
}
