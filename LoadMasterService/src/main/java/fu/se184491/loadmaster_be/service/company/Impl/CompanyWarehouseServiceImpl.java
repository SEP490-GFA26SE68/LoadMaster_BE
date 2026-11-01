package fu.se184491.loadmaster_be.service.company.Impl;


import fu.se184491.loadmaster_be.dto.request.company.CompanyWarehouseRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyWarehouseResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository;
import fu.se184491.loadmaster_be.service.company.CompanyWarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyWarehouseServiceImpl implements CompanyWarehouseService {

    private final CompanyWarehouseRepository warehouseRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<CompanyWarehouseResponse> getWarehouses(
            boolean activeOnly
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        List<CompanyWarehouse> warehouses = activeOnly
                ? warehouseRepository
                .findByCompanyIdAndIsActiveTrueOrderByWarehouseNameAsc(companyId)
                : warehouseRepository
                .findByCompanyIdOrderByIsDefaultDescWarehouseNameAsc(companyId);

        return warehouses.stream()
                .map(CompanyWarehouseResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyWarehouseResponse getWarehouse(Long warehouseId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        CompanyWarehouse warehouse = warehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.INVALID_INPUT)
                );

        return CompanyWarehouseResponse.fromEntity(warehouse);
    }

    @Transactional
    public CompanyWarehouseResponse createWarehouse(
            CompanyWarehouseRequest request
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.COMPANY_NOT_FOUND)
                );

        // Kho đầu tiên của công ty sẽ được chọn mặc định.
        boolean makeDefault = warehouseRepository
                .findByCompanyIdOrderByIsDefaultDescWarehouseNameAsc(companyId)
                .isEmpty();

        CompanyWarehouse warehouse = CompanyWarehouse.builder()
                .company(company)
                .warehouseName(request.warehouseName().trim())
                .address(request.address().trim())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .isDefault(makeDefault)
                .isActive(true)
                .build();

        CompanyWarehouse saved = warehouseRepository.save(warehouse);

        return CompanyWarehouseResponse.fromEntity(saved);
    }

    @Transactional
    public CompanyWarehouseResponse updateWarehouse(
            Long warehouseId,
            CompanyWarehouseRequest request
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        CompanyWarehouse warehouse = warehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.INVALID_INPUT)
                );

        warehouse.setWarehouseName(request.warehouseName().trim());
        warehouse.setAddress(request.address().trim());
        warehouse.setLatitude(request.latitude());
        warehouse.setLongitude(request.longitude());

        return CompanyWarehouseResponse.fromEntity(warehouse);
    }

    @Transactional
    public CompanyWarehouseResponse setDefaultWarehouse(
            Long warehouseId
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        CompanyWarehouse selected = warehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.INVALID_INPUT)
                );

        if (!selected.isActive()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        if (selected.isDefault()) {
            return CompanyWarehouseResponse.fromEntity(selected);
        }

        // Bỏ cờ mặc định cũ trước để không vi phạm
        // unique index trong PostgreSQL.
        warehouseRepository.clearDefaultWarehouse(companyId);

        // Repository dùng clearAutomatically = true,
        // nên phải truy vấn lại entity.
        CompanyWarehouse warehouse = warehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.INVALID_INPUT)
                );

        warehouse.setDefault(true);
        warehouseRepository.flush();

        return CompanyWarehouseResponse.fromEntity(warehouse);
    }

    @Transactional
    public CompanyWarehouseResponse setWarehouseActive(
            Long warehouseId,
            boolean active
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        CompanyWarehouse warehouse = warehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.INVALID_INPUT)
                );

        // Không cho ngừng hoạt động kho mặc định.
        // Cần chọn kho mặc định khác trước.
        if (!active && warehouse.isDefault()) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        warehouse.setActive(active);

        return CompanyWarehouseResponse.fromEntity(warehouse);
    }
}
