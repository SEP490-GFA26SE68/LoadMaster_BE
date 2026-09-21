package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.vehicle.VehicleTypeResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VehicleTypeService {
    VehicleTypeResponse createVehicleType(Long companyId, VehicleTypeRequest request);
    VehicleTypeResponse updateVehicleType(Long companyId, Long id, VehicleTypeRequest request);
    VehicleTypeResponse getVehicleTypeById(Long companyId, Long id);
    Page<VehicleTypeResponse> getAllVehicleTypes(Long companyId, Pageable pageable);
    void deleteVehicleType(Long companyId, Long id);
}
