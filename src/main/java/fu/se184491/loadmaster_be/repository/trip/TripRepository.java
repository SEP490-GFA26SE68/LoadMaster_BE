package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {
}
