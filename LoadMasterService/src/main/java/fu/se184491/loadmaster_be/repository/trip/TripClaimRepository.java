package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.TripClaim;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripClaimRepository extends JpaRepository<TripClaim, Long> {

    @EntityGraph(attributePaths = {"trip", "driver", "trip.originWarehouse"})
    List<TripClaim> findByCompanyIdAndStatusAndTripDriverIsNullAndTripStatusInOrderByCreatedAtAscIdAsc(
            Long companyId,
            TripClaimStatus status,
            Collection<TripStatus> tripStatuses
    );

    List<TripClaim> findByTripIdAndCompanyId(Long tripId, Long companyId);

    List<TripClaim> findByTripIdAndCompanyIdOrderByCreatedAtDesc(Long tripId, Long companyId);

    List<TripClaim> findByTripIdAndCompanyIdAndStatus(Long tripId, Long companyId, TripClaimStatus status);

    List<TripClaim> findByDriverIdAndCompanyId(Long driverId, Long companyId);

    boolean existsByTripIdAndDriverIdAndStatus(Long tripId, Long driverId, TripClaimStatus status);

    Optional<TripClaim> findByIdAndCompanyId(Long id, Long companyId);

    List<TripClaim> findByTripId(Long tripId);
}
