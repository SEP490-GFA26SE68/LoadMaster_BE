package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Fetches UnplacedPackages for a given LoadPlan.
 */
@Repository
public interface UnplacedPackageRepository extends JpaRepository<UnplacedPackage, Long> {

    @Query("""
            SELECT up FROM UnplacedPackage up
            WHERE up.loadPlan.id = :loadPlanId
            """)
    List<UnplacedPackage> findByLoadPlanId(@Param("loadPlanId") Long loadPlanId);
}
