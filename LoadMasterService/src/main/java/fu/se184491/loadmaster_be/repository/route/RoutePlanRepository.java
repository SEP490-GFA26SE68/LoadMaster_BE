package fu.se184491.loadmaster_be.repository.route;

import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    Optional<RoutePlan> findByTripIdAndIsActiveTrue(Long tripId);

    Optional<RoutePlan> findByIdAndTripCompanyId(Long id, Long companyId);

    Optional<RoutePlan> findByTripIdAndTripCompanyIdAndIsActiveTrue(Long tripId, Long companyId);

    Page<RoutePlan> findByTripIdAndTripCompanyIdOrderByPlanVersionDesc(Long tripId, Long companyId, Pageable pageable);

    List<RoutePlan> findByTripId(Long tripId);

    @Modifying
    @Query("UPDATE RoutePlan rp SET rp.isActive = false WHERE rp.trip.id = :tripId")
    void deactivateAllByTripId(@Param("tripId") Long tripId);
}
