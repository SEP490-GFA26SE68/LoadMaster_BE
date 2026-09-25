package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlacementConfirmationRepository extends JpaRepository<PlacementConfirmation, Long> {

    Optional<PlacementConfirmation> findByPlacementId(Long placementId);

    List<PlacementConfirmation> findByPlacementLoadPlanId(Long loadPlanId);

    boolean existsByPlacementId(Long placementId);

    void deleteByPlacementId(Long placementId);
}
