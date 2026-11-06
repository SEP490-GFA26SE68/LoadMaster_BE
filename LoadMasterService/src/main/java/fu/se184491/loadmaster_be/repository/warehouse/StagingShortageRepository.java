package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StagingShortageRepository extends JpaRepository<StagingShortage, Long> {

    List<StagingShortage> findByTripIdAndStatusOrderByReportedAtAsc(Long tripId, ShortageStatus status);

    Optional<StagingShortage> findFirstByTripIdAndCargoPackageIdAndStatus(Long tripId, Long packageId, ShortageStatus status);
}
