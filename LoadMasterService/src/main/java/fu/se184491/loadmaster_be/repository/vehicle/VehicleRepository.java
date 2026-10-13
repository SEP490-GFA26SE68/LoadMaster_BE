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
    boolean existsByLicensePlateIgnoreCase(String licensePlate);
    Page<Vehicle> findByCompanyId(Long companyId, Pageable pageable);
    Page<Vehicle> findAllByCompanyId(
            Long companyId,
            Pageable pageable
    );

    Page<Vehicle> findAllByCompanyIdAndNameContainingIgnoreCaseOrCompanyIdAndLicensePlateContainingIgnoreCase(
            Long companyId1,
            String name,
            Long companyId2,
            String licensePlate,
            Pageable pageable
    );

    long countByVehicleTypeId(Long vehicleTypeId);
}
