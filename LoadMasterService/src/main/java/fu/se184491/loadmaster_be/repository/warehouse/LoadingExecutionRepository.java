package fu.se184491.loadmaster_be.repository.warehouse;

import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoadingExecutionRepository extends JpaRepository<LoadingExecution, Long> {

    Optional<LoadingExecution> findByLoadPlanId(Long loadPlanId);

    Optional<LoadingExecution> findByLoadPlanJobTripId(Long tripId);

    List<LoadingExecution> findByWorkerId(Long workerId);

    boolean existsByLoadPlanIdAndStatus(Long loadPlanId, LoadingExecutionStatus status);

    boolean existsByLoadPlanJobTripIdAndStatus(Long tripId, LoadingExecutionStatus status);
}
