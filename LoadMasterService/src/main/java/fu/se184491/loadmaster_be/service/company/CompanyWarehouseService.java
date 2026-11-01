package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.dto.request.company.CompanyWarehouseRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyWarehouseResponse;

import java.util.List;

public interface CompanyWarehouseService {
    List<CompanyWarehouseResponse> getWarehouses(
            boolean activeOnly
    );

    CompanyWarehouseResponse getWarehouse(Long warehouseId);

    CompanyWarehouseResponse createWarehouse(
            CompanyWarehouseRequest request
    );

    CompanyWarehouseResponse updateWarehouse(
            Long warehouseId,
            CompanyWarehouseRequest request
    );

    CompanyWarehouseResponse setDefaultWarehouse(
            Long warehouseId
    );

    CompanyWarehouseResponse setWarehouseActive(
            Long warehouseId,
            boolean active
    );
}
