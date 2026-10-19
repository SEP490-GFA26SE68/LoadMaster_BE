package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VehicleTypeService {
    VehicleTypeResponse createVehicleType(
            CreateVehicleTypeRequest request
    );

    void deleteVehicleType(Long companyId, Long id);

    PageResponse<VehicleTypeResponse> getVehicleTypes(
            int page,
            int size,
            String search
    );
}
