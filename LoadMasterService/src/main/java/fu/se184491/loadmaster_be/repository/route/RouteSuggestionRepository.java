package fu.se184491.loadmaster_be.repository.route;

import fu.se184491.loadmaster_be.entity.route.RouteSuggestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteSuggestionRepository extends JpaRepository<RouteSuggestion, Long> {

    List<RouteSuggestion> findByRouteLegId(Long routeLegId);

    Page<RouteSuggestion> findByRouteLegIdAndRouteLegRoutePlanTripCompanyId(Long routeLegId, Long companyId, Pageable pageable);

    Optional<RouteSuggestion> findByIdAndRouteLegRoutePlanTripCompanyId(Long id, Long companyId);
}
