package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackageRepository extends JpaRepository<CargoPackage, Long>, JpaSpecificationExecutor<CargoPackage> {

    List<CargoPackage> findByOrderId(Long orderId);

    Page<CargoPackage> findByOrderId(Long orderId, Pageable pageable);

    List<CargoPackage> findByPackageTypeId(Long packageTypeId);

    Page<CargoPackage> findByPackageTypeId(Long packageTypeId, Pageable pageable);

    Optional<CargoPackage> findByTrackingBarcode(String trackingBarcode);

    boolean existsByTrackingBarcode(String trackingBarcode);

    boolean existsByTrackingBarcodeAndIdNot(String trackingBarcode, Long id);
}
