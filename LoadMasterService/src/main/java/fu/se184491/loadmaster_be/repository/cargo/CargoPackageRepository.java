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

    Optional<CargoPackage> findByQrToken(String qrToken);

    boolean existsByQrToken(String qrToken);

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
}
