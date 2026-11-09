package fu.se184491.loadmaster_be.repository.pickup;

import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PickupRequestPackageRepository extends JpaRepository<PickupRequestPackage, Long> {

    Optional<PickupRequestPackage> findByCargoPackageId(Long cargoPackageId);

    /** Kiện nhận giao ở điểm {@code stopId} của các yêu cầu ở trạng thái {@code statuses}, theo yêu cầu rồi thứ tự kiện. */
    @Query("""
            SELECT line FROM PickupRequestPackage line
            JOIN line.pickupRequest request
            WHERE request.deliveryStop.id = :stopId
              AND request.status IN :statuses
              AND line.cargoPackage IS NOT NULL
            ORDER BY request.id ASC, line.lineIndex ASC
            """)
    List<PickupRequestPackage> findDeliveriesAtStop(@Param("stopId") Long stopId,
                                                    @Param("statuses") Collection<PickupRequestStatus> statuses);
}
