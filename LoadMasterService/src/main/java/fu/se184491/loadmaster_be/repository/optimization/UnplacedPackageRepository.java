package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnplacedPackageRepository extends JpaRepository<UnplacedPackage, Long> {

    List<UnplacedPackage> findByLoadPlanId(Long loadPlanId);

    long countByLoadPlanId(Long loadPlanId);
}
