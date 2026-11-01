
package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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


    /**
     * Đếm số điểm giao của nhiều chuyến trong một truy vấn.
     * Dùng để hiển thị cột "Điểm giao" trong danh sách chuyến.
     *
     * Object[0]: tripId (Long)
     * Object[1]: stopCount (Long)
     */
    @Query("""
        SELECT ds.trip.id, COUNT(ds.id)
        FROM DeliveryStop ds
        WHERE ds.trip.id IN :tripIds
        GROUP BY ds.trip.id
        """)
    List<Object[]> countStopsByTripIds(
            @Param("tripIds") Collection<Long> tripIds
    );


    // Lấy thứ tự điểm giao lớn nhất trong chuyến.
    @Query("""
            SELECT COALESCE(MAX(ds.stopSequence), 0)
            FROM DeliveryStop ds
            WHERE ds.trip.id = :tripId
            """)
    Integer findMaxStopSequenceByTripId(
            @Param("tripId") Long tripId
    );

    List<DeliveryStop> findByTripIdAndDeliveryRequirementId(
            Long tripId,
            Long deliveryRequirementId
    );

    // Kiểm tra yêu cầu giao đã sinh điểm giao hay chưa.
    boolean existsByDeliveryRequirementId(Long deliveryRequirementId);
}
