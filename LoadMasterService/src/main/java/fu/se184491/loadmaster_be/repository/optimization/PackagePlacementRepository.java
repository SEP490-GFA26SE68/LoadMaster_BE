package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagePlacementRepository extends JpaRepository<PackagePlacement, Long> {

    List<PackagePlacement> findByLoadPlanId(Long loadPlanId);

    List<PackagePlacement> findByLoadPlanIdAndPinnedTrue(Long loadPlanId);

    Optional<PackagePlacement> findByIdAndLoadPlanId(Long id, Long loadPlanId);

    List<PackagePlacement> findByLoadPlanIdOrderByStepSequenceAsc(Long loadPlanId);
}
