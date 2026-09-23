package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Fetches CargoPackages that belong to a given trip,
 * by traversing: Trip -> DeliveryStop -> TransportOrder -> CargoPackage.
 */
@Repository
public interface CargoPackageForTripRepository extends JpaRepository<CargoPackage, Long> {

    @Query("""
            SELECT cp FROM CargoPackage cp
            JOIN cp.order o
            JOIN o.deliveryStop ds
            JOIN ds.trip t
            WHERE t.id = :tripId
            """)
    List<CargoPackage> findPackagesByTripId(@Param("tripId") Long tripId);
}
