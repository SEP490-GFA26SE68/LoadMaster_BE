package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoadPlanRepository extends JpaRepository<LoadPlan, Long> {

    Optional<LoadPlan> findFirstByJobTripIdAndApprovedTrueOrderByVersionDesc(Long tripId);

    List<LoadPlan> findByJobTripCompanyIdAndApprovedTrue(Long companyId);
}
