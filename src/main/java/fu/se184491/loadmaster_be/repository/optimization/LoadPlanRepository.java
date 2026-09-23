package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoadPlanRepository extends JpaRepository<LoadPlan, Long> {

    /**
     * Fetches all LoadPlans for a given OptimizationJob, with PackagePlacements
     * eagerly fetched to avoid N+1 when rendering the /plans response.
     */
    @Query("""
            SELECT lp FROM LoadPlan lp
            LEFT JOIN FETCH lp.job j
            WHERE j.id = :jobId
            ORDER BY lp.id ASC
            """)
    List<LoadPlan> findByJobId(@Param("jobId") Long jobId);
}
