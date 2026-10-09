package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VehicleService {
    VehicleResponse createVehicle(
            CreateVehicleRequest request
    );

    PageResponse<VehicleResponse> getVehicles(
            int page,
            int size,
            String search,
            Long vehicleTypeId
    );

}
