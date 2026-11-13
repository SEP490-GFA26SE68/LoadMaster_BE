package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CargoPackageRepository extends JpaRepository<CargoPackage, Long> {

    // Scalar IDs keep package entities unloaded until the locking query acquires the rows.
    @Query("select p.id from DeliveryRequirement r join r.packages p where r.id = :requirementId order by p.id")
    List<Long> findIdsByRequirementId(@Param("requirementId") Long requirementId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CargoPackage p where p.id in :ids order by p.id")
    List<CargoPackage> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    Optional<CargoPackage> findByQrToken(String qrToken);

    boolean existsByQrToken(String qrToken);

    boolean existsByTrackingBarcode(String trackingBarcode);

    boolean existsByPackageCode(String packageCode);

    List<CargoPackage> findByOrderDeliveryStopId(Long stopId);

    List<CargoPackage> findAllByIdInAndOrderCompanyId(List<Long> ids, Long companyId);

    List<CargoPackage> findByOrderDeliveryStopTripIdOrderByIdAsc(Long tripId);

    /** Kiện của một điểm giao, theo liên kết điểm giao → yêu cầu giao → kiện. */
    @Query("""
            SELECT DISTINCT p
            FROM DeliveryStop s
            JOIN s.deliveryRequirement r
            JOIN r.packages p
            WHERE s.id = :stopId
            ORDER BY p.id
            """)
    List<CargoPackage> findByRequirementStopId(@Param("stopId") Long stopId);

    /** Kiện của mọi yêu cầu giao đang gán vào chuyến. */
    @Query("""
            SELECT DISTINCT p
            FROM DeliveryRequirement r
            JOIN r.packages p
            WHERE r.trip.id = :tripId
            ORDER BY p.id
            """)
    List<CargoPackage> findByRequirementTripId(@Param("tripId") Long tripId);

    /** Số kiện kho đã bỏ khỏi chuyến (thiếu, hỏng) khi chúng còn thuộc yêu cầu giao này. */
    long countByDroppedRequirementId(Long requirementId);
}
