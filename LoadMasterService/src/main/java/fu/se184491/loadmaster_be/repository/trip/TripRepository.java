package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripRepository extends
        JpaRepository<Trip, Long>,
        JpaSpecificationExecutor<Trip> {
    Optional<Trip> findByTripCode(String tripCode);

    List<Trip> findByCompanyId(Long companyId);

    List<Trip> findByCompanyIdAndStatus(Long companyId, TripStatus status);

    List<Trip> findByDriver_IdOrderByDepartureTimeDesc(Long driverId);



    Optional<Trip> findByIdAndCompanyId(Long id, Long companyId);

    Optional<Trip> findByTripCodeAndCompanyId(String tripCode, Long companyId);
    boolean existsByVehicleIdAndStatusIn(
            Long vehicleId,
            Collection<TripStatus> statuses
    );

    boolean existsByVehicleId(Long vehicleId);

    List<Trip> findByVehicleIdAndStatusIn(Long vehicleId, Collection<TripStatus> statuses);

    List<Trip> findByVehicle_VehicleType_IdAndStatusIn(Long vehicleTypeId, Collection<TripStatus> statuses);

    Page<Trip> findByCompanyId(Long companyId, Pageable pageable);

    /** Chuyến của công ty có giờ xuất phát trong [start, end). */
    @Query("""
        SELECT t
        FROM Trip t
        WHERE t.company.id = :companyId
          AND t.departureTime >= :start
          AND t.departureTime < :end
        """)
    List<Trip> findDepartingBetween(
            @Param("companyId") Long companyId,
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end
    );

    /**
     * Khoá dòng chuyến tới hết transaction: các lệnh đổi trạng thái chuyến chạy lần lượt. Không flush trước khi khoá:
     * chuyến đọc trước đó (cột JSON luôn bị coi là đã sửa) sẽ ghi đè trạng thái lệnh khác vừa ghi.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query("SELECT t FROM Trip t WHERE t.id = :tripId")
    Optional<Trip> findByIdForUpdate(@Param("tripId") Long tripId);

    /**
     * Khóa chuyến trong transaction khi gán yêu cầu giao.
     * Giúp các request gán cùng một chuyến được xử lý tuần tự.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT t
        FROM Trip t
        WHERE t.id = :tripId
          AND t.company.id = :companyId
        """)
    Optional<Trip> findByIdAndCompanyIdForUpdate(
            @Param("tripId") Long tripId,
            @Param("companyId") Long companyId
    );
}
