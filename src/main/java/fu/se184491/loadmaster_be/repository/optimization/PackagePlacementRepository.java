package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackagePlacementRepository extends JpaRepository<PackagePlacement, Long> {

    @Query("""
            SELECT pp FROM PackagePlacement pp
            WHERE pp.loadPlan.id = :loadPlanId
            ORDER BY pp.stepSequence ASC
            """)
    List<PackagePlacement> findByLoadPlanId(@Param("loadPlanId") Long loadPlanId);
}
