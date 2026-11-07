package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StagingConfirmationRepository extends JpaRepository<StagingConfirmation, Long> {

    List<StagingConfirmation> findByTripId(Long tripId);

    Optional<StagingConfirmation> findByTripIdAndCargoPackageId(Long tripId, Long packageId);

    void deleteByTripIdAndCargoPackageId(Long tripId, Long packageId);
}
