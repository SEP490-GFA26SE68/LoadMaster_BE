package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviationRepository extends JpaRepository<Deviation, Long> {

    List<Deviation> findByPlacementId(Long placementId);

    List<Deviation> findByPlacementLoadPlanId(Long loadPlanId);

    boolean existsByPlacementId(Long placementId);

    long countByPlacementLoadPlanId(Long loadPlanId);

    void deleteByPlacementId(Long placementId);
}
