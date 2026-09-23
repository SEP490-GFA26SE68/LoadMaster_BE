package fu.se184491.loadmaster_be.repository.trip;

import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Fetches DeliveryStops for a given trip, ordered by stop_sequence.
 */
@Repository
public interface DeliveryStopRepository extends JpaRepository<DeliveryStop, Long> {

    @Query("""
            SELECT ds FROM DeliveryStop ds
            WHERE ds.trip.id = :tripId
            ORDER BY ds.stopSequence ASC
            """)
    List<DeliveryStop> findByTripIdOrderBySequence(@Param("tripId") Long tripId);
}
