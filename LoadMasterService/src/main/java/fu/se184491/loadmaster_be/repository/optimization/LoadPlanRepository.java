package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoadPlanRepository extends JpaRepository<LoadPlan, Long> {

    Optional<LoadPlan> findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(Long tripId);

    List<LoadPlan> findByJobTripCompanyIdAndApprovedTrue(Long companyId);

    @Modifying
    @Query("UPDATE LoadPlan p SET p.approved = false WHERE p.approved = true"
            + " AND p.job.id IN (SELECT j.id FROM OptimizationJob j WHERE j.trip.id = :tripId)")
    int unapproveAllByTripId(@Param("tripId") Long tripId);
}
