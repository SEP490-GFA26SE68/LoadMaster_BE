package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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



    Optional<Trip> findByIdAndCompanyId(Long id, Long companyId);

    Optional<Trip> findByTripCodeAndCompanyId(String tripCode, Long companyId);
    boolean existsByVehicleIdAndStatusIn(
            Long vehicleId,
            Collection<TripStatus> statuses
    );

    Page<Trip> findByCompanyId(Long companyId, Pageable pageable);

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
