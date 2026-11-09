package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.entity.warehouse.LoadingDamage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoadingDamageRepository extends JpaRepository<LoadingDamage, Long> {

    List<LoadingDamage> findByPlacementLoadPlanId(Long loadPlanId);

    boolean existsByPlacementId(Long placementId);
}
