package fu.se184491.loadmaster_be.repository.driver;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ManualUnloadConfirmationRepository extends JpaRepository<ManualUnloadConfirmation, Long> {

    List<ManualUnloadConfirmation> findByDeliveryStopIdOrderByRequestedAtAsc(Long deliveryStopId);

    List<ManualUnloadConfirmation> findByTripIdAndStatusOrderByRequestedAtAsc(Long tripId, ManualConfirmStatus status);

    long countByDeliveryStopIdAndStatus(Long deliveryStopId, ManualConfirmStatus status);

    boolean existsByCargoPackageIdAndStatus(Long packageId, ManualConfirmStatus status);

    /** Mọi xác nhận tay của chuyến (mọi bước), theo thứ tự gửi. */
    List<ManualUnloadConfirmation> findByTripIdOrderByRequestedAtAsc(Long tripId);

    /**
     * Số xác nhận tay ở trạng thái {@code status} theo chuyến của công ty, chỉ khi bước khớp pha của chuyến như danh
     * sách chờ duyệt: bước dỡ (kể cả bản ghi không có bước) lúc {@code unloadingTripStatus}, các bước
     * {@code warehouseSteps} lúc {@code warehouseTripStatus}. Chỉ đọc.
     */
    @Query("""
        SELECT t.tripCode AS tripCode, COUNT(c) AS total
        FROM ManualUnloadConfirmation c
        JOIN c.trip t
        WHERE t.company.id = :companyId
          AND c.status = :status
          AND ((t.status = :unloadingTripStatus AND (c.step IS NULL OR c.step = :unloadingStep))
            OR (t.status = :warehouseTripStatus AND c.step IN :warehouseSteps))
        GROUP BY t.tripCode
        """)
    List<TripCodeCount> countByTripMatchingPhase(
            @Param("companyId") Long companyId,
            @Param("status") ManualConfirmStatus status,
            @Param("unloadingTripStatus") TripStatus unloadingTripStatus,
            @Param("unloadingStep") ManualConfirmStep unloadingStep,
            @Param("warehouseTripStatus") TripStatus warehouseTripStatus,
            @Param("warehouseSteps") Collection<ManualConfirmStep> warehouseSteps
    );
}
