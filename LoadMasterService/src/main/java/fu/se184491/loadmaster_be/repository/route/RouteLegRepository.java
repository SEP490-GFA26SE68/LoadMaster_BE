package fu.se184491.loadmaster_be.repository.route;

import fu.se184491.loadmaster_be.entity.route.RouteLeg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteLegRepository extends JpaRepository<RouteLeg, Long> {

    List<RouteLeg> findByRoutePlanIdOrderByLegSequenceAsc(Long routePlanId);

    Page<RouteLeg> findByRoutePlanIdAndRoutePlanTripCompanyIdOrderByLegSequenceAsc(Long routePlanId, Long companyId, Pageable pageable);

    Optional<RouteLeg> findByIdAndRoutePlanTripCompanyId(Long id, Long companyId);
}
