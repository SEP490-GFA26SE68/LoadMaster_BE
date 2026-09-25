package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findByTripCode(String tripCode);

    List<Trip> findByCompanyId(Long companyId);

    List<Trip> findByCompanyIdAndStatus(Long companyId, TripStatus status);

    List<Trip> findByVehicleDriverIdAndStatus(Long driverId, TripStatus status);

    List<Trip> findByStatus(TripStatus status);
}
