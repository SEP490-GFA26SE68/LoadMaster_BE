package fu.se184491.loadmaster_be.dto.response.company;


import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;

import java.math.BigDecimal;
import java.time.Instant;

public record CompanyWarehouseResponse(
        Long id,
        Long companyId,
        String warehouseName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean isDefault,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {

    public static CompanyWarehouseResponse fromEntity(
            CompanyWarehouse warehouse
    ) {
        return new CompanyWarehouseResponse(
                warehouse.getId(),
                warehouse.getCompany().getId(),
                warehouse.getWarehouseName(),
                warehouse.getAddress(),
                warehouse.getLatitude(),
                warehouse.getLongitude(),
                warehouse.isDefault(),
                warehouse.isActive(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt()
        );
    }
}
