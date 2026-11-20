package fu.se184491.loadmaster_be.repository.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.repository.trip.TripCodeCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripExceptionRepository extends JpaRepository<TripException, Long> {

    List<TripException> findByTripIdOrderByReportedAtAsc(Long tripId);

    Optional<TripException> findByIdAndTripId(Long id, Long tripId);

    List<TripException> findByStatusAndReportedAtBefore(TripExceptionStatus status, LocalDateTime reportedAt);

    boolean existsByTripIdAndStatusIn(Long tripId, Collection<TripExceptionStatus> statuses);

    /** Số sự cố ở một trong {@code statuses} theo chuyến của công ty đang ở trạng thái {@code tripStatus}. Chỉ đọc. */
    @Query("""
        SELECT t.tripCode AS tripCode, COUNT(e) AS total
        FROM TripException e
        JOIN e.trip t
        WHERE t.company.id = :companyId
          AND t.status = :tripStatus
          AND e.status IN :statuses
        GROUP BY t.tripCode
        """)
    List<TripCodeCount> countByTripAndStatusIn(
            @Param("companyId") Long companyId,
            @Param("tripStatus") TripStatus tripStatus,
            @Param("statuses") Collection<TripExceptionStatus> statuses
    );
}
