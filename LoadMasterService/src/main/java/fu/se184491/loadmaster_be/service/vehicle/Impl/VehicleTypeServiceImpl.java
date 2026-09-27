package fu.se184491.loadmaster_be.service.vehicle.Impl;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VehicleTypeServiceImpl implements VehicleTypeService {

    private final VehicleTypeRepository vehicleTypeRepository;
    // We would need CompanyRepository here to get company reference, simulating with EntityManager or proxy
    // Assuming companyId is valid from the context, we can use a proxy reference to avoid DB hit for company lookup
    private final jakarta.persistence.EntityManager entityManager;

    private VehicleTypeResponse mapToResponse(VehicleType entity) {
        return VehicleTypeResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .innerLength(entity.getInnerLength())
                .innerWidth(entity.getInnerWidth())
                .innerHeight(entity.getInnerHeight())
                .maxPayloadKg(entity.getMaxPayloadKg())
                .companyId(entity.getCompany() != null ? entity.getCompany().getId() : null)
                .build();
    }

    @Override
    @Transactional
    public VehicleTypeResponse createVehicleType(Long companyId, VehicleTypeRequest request) {
        Company companyProxy = entityManager.getReference(Company.class, companyId);
        
        VehicleType type = VehicleType.builder()
                .company(companyProxy)
                .name(request.getName())
                .innerLength(request.getInnerLength())
                .innerWidth(request.getInnerWidth())
                .innerHeight(request.getInnerHeight())
                .maxPayloadKg(request.getMaxPayloadKg())
                .build();
                
        type = vehicleTypeRepository.save(type);
        return mapToResponse(type);
    }

    @Override
    @Transactional
    public VehicleTypeResponse updateVehicleType(Long companyId, Long id, VehicleTypeRequest request) {
        VehicleType type = vehicleTypeRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
                
        if (!type.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        
        type.setName(request.getName());
        type.setInnerLength(request.getInnerLength());
        type.setInnerWidth(request.getInnerWidth());
        type.setInnerHeight(request.getInnerHeight());
        type.setMaxPayloadKg(request.getMaxPayloadKg());
        
        type = vehicleTypeRepository.save(type);
        return mapToResponse(type);
    }

    @Override
    public VehicleTypeResponse getVehicleTypeById(Long companyId, Long id) {
        VehicleType type = vehicleTypeRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
                
        if (!type.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        
        return mapToResponse(type);
    }

    @Override
    public Page<VehicleTypeResponse> getAllVehicleTypes(Long companyId, Pageable pageable) {
        return vehicleTypeRepository.findByCompanyId(companyId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional
    public void deleteVehicleType(Long companyId, Long id) {
        VehicleType type = vehicleTypeRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
                
        if (!type.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        
        vehicleTypeRepository.delete(type);
    }
}
