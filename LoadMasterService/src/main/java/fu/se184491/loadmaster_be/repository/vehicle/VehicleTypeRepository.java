package fu.se184491.loadmaster_be.repository.vehicle;

import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleTypeRepository extends JpaRepository<VehicleType, Long> {
    Page<VehicleType> findByCompanyId(Long companyId, Pageable pageable);
}
