package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EtaRiskAlertRepository extends JpaRepository<EtaRiskAlert, Long> {

    List<EtaRiskAlert> findByTripIdOrderByCreatedAtAsc(Long tripId);

    Optional<EtaRiskAlert> findFirstByTripIdAndStopIdAndStatusOrderByCreatedAtDesc(
            Long tripId,
            Long stopId,
            DeadlineStatus status
    );
}
