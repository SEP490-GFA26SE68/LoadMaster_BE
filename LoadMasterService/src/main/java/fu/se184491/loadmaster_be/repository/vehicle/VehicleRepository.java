package fu.se184491.loadmaster_be.repository.vehicle;

import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long>, JpaSpecificationExecutor<Vehicle> {
    Optional<Vehicle> findByLicensePlate(String licensePlate);
    Page<Vehicle> findByCompanyId(Long companyId, Pageable pageable);
    boolean existsByLicensePlate(String licensePlate);
}
