package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OptimizationJobRepository extends JpaRepository<OptimizationJob, Long> {

    Optional<OptimizationJob> findByJobUuid(String jobUuid);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from OptimizationJob j where j.jobUuid = :reference")
    Optional<OptimizationJob> findByJobUuidForUpdate(@org.springframework.data.repository.query.Param("reference") String reference);
}
