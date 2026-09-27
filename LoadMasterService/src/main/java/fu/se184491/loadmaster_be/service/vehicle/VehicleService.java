package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VehicleService {
    VehicleResponse createVehicle(Long companyId, VehicleRequest request);
    VehicleResponse updateVehicle(Long companyId, Long id, VehicleRequest request);
    VehicleResponse getVehicleById(Long companyId, Long id);
    Page<VehicleResponse> getAllVehicles(Long companyId, Long vehicleTypeId, Long driverUserId, Pageable pageable);
    void deleteVehicle(Long companyId, Long id);
}
