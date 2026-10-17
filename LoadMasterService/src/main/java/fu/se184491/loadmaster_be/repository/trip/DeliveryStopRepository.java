
package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryStopRepository extends JpaRepository<DeliveryStop, Long> {

    List<DeliveryStop> findByTripIdOrderByStopSequenceAsc(Long tripId);

    Optional<DeliveryStop> findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(
            Long tripId,
            DeliveryStopStatus status
    );

    long countByTripId(Long tripId);

    // Lấy thứ tự điểm giao lớn nhất trong chuyến.
    @Query("""
            SELECT COALESCE(MAX(ds.stopSequence), 0)
            FROM DeliveryStop ds
            WHERE ds.trip.id = :tripId
            """)
    Integer findMaxStopSequenceByTripId(
            @Param("tripId") Long tripId
    );

    // Kiểm tra yêu cầu giao đã sinh điểm giao hay chưa.
    boolean existsByDeliveryRequirementId(Long deliveryRequirementId);
}
