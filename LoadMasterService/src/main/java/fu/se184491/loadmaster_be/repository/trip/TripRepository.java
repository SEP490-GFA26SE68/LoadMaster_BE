package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripCode(String tripCode);

    List<Trip> findByCompanyId(Long companyId);

    List<Trip> findByCompanyIdAndStatus(Long companyId, TripStatus status);



    Optional<Trip> findByIdAndCompanyId(Long id, Long companyId);

    Optional<Trip> findByTripCodeAndCompanyId(String tripCode, Long companyId);
    boolean existsByVehicleIdAndStatusIn(
            Long vehicleId,
            Collection<TripStatus> statuses
    );



}
