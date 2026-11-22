package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.monitoring.EtaRiskAlert;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Theo chuyến của công ty ở trạng thái {@code tripStatus}: số điểm chưa tới đã có cảnh báo ở một trong
     * {@code statuses} cho đúng hạn hiện tại của điểm (hạn đã gia hạn thì cảnh báo cũ không tính). Chỉ đọc.
     */
    @Query("""
        SELECT t.tripCode AS tripCode, COUNT(DISTINCT a.stopId) AS total
        FROM EtaRiskAlert a
        JOIN a.trip t
        JOIN DeliveryStop s ON s.id = a.stopId
        WHERE t.company.id = :companyId
          AND t.status = :tripStatus
          AND s.trip = t
          AND a.status IN :statuses
          AND a.deadline = s.plannedArrival
          AND s.actualArrival IS NULL
          AND s.status <> :completed
        GROUP BY t.tripCode
        """)
    List<TripCodeCount> countStopsAtRiskByTrip(
            @Param("companyId") Long companyId,
            @Param("tripStatus") TripStatus tripStatus,
            @Param("statuses") Collection<DeadlineStatus> statuses,
            @Param("completed") DeliveryStopStatus completed
    );
}
