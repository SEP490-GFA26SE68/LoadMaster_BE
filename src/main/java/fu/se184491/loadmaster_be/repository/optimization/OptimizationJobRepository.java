package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OptimizationJobRepository extends JpaRepository<OptimizationJob, Long> {

    Optional<OptimizationJob> findByJobUuid(String jobUuid);

    List<OptimizationJob> findByTripId(Long tripId);

    List<OptimizationJob> findByStatus(OptimizationJobStatus status);
}
