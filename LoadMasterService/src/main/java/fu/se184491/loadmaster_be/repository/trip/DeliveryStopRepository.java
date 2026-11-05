
package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // Như trên nhưng bỏ qua điểm giao của chuyến không còn hiệu lực (đã huỷ giữ lại điểm để xem lịch sử).
    boolean existsByDeliveryRequirementIdAndTrip_StatusNot(
            Long deliveryRequirementId,
            TripStatus status
    );

    /**
     * Điểm giao của một kiện theo liên kết kiện → yêu cầu giao → điểm giao,
     * chỉ tính điểm thuộc đúng chuyến yêu cầu giao đang gán.
     */
    @Query("""
            SELECT s
            FROM DeliveryStop s
            JOIN s.deliveryRequirement r
            JOIN r.packages p
            WHERE p.id = :packageId
              AND r.trip.id = s.trip.id
            ORDER BY s.id
            """)
    List<DeliveryStop> findRequirementStopsByPackageId(
            @Param("packageId") Long packageId
    );

    // Gỡ các tham chiếu tới một điểm giao trước khi xoá nó (gỡ yêu cầu giao khỏi chuyến).

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RouteLeg l WHERE l.startStop.id = :stopId OR l.endStop.id = :stopId")
    int deleteRouteLegsTouchingStop(@Param("stopId") Long stopId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE PackagePlacement p SET p.stopZone = null WHERE p.stopZone.id = :stopId")
    int detachPlacementsFromStop(@Param("stopId") Long stopId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE TransportOrder o SET o.deliveryStop = null WHERE o.deliveryStop.id = :stopId")
    int detachOrdersFromStop(@Param("stopId") Long stopId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE TripCargoPackage t SET t.deliveryStop = null WHERE t.deliveryStop.id = :stopId")
    int detachTripCargoPackagesFromStop(@Param("stopId") Long stopId);
}
