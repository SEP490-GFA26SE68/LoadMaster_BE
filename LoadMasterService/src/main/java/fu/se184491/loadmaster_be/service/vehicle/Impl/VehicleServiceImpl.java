package fu.se184491.loadmaster_be.service.vehicle.Impl;

import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final UserRepository userRepository;

    @Override
    public VehicleResponse createVehicle(Long companyId, VehicleRequest request) {
        
        VehicleType vehicleType = vehicleTypeRepository.findById(request.getVehicleTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
                
        if (!vehicleType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        
        if (vehicleRepository.existsByLicensePlate(request.getLicensePlate())) {
            throw new AppException(ErrorCode.INVALID_INPUT); // Can map to specific error if needed
        }

        User driver = null;
        if (request.getDriverUserId() != null) {
            driver = userRepository.findById(request.getDriverUserId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            if (!driver.getCompany().getId().equals(companyId)) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }

        Vehicle vehicle = Vehicle.builder()
                .company(Company.builder().id(companyId).build())
                .vehicleType(vehicleType)
                .licensePlate(request.getLicensePlate())
                .frontAxleLimitKg(request.getFrontAxleLimitKg())
                .rearAxleLimitKg(request.getRearAxleLimitKg())
                .driver(driver)
                .build();

        vehicle = vehicleRepository.save(vehicle);
        return mapToResponse(vehicle);
    }

    @Override
    public VehicleResponse updateVehicle(Long companyId, Long id, VehicleRequest request) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));

        if (!vehicle.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        VehicleType vehicleType = vehicleTypeRepository.findById(request.getVehicleTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));

        if (!vehicleType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (!vehicle.getLicensePlate().equals(request.getLicensePlate()) &&
                vehicleRepository.existsByLicensePlate(request.getLicensePlate())) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        User driver = null;
        if (request.getDriverUserId() != null) {
            driver = userRepository.findById(request.getDriverUserId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            if (!driver.getCompany().getId().equals(companyId)) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }

        vehicle.setVehicleType(vehicleType);
        vehicle.setLicensePlate(request.getLicensePlate());
        vehicle.setFrontAxleLimitKg(request.getFrontAxleLimitKg());
        vehicle.setRearAxleLimitKg(request.getRearAxleLimitKg());
        vehicle.setDriver(driver);

        vehicle = vehicleRepository.save(vehicle);
        return mapToResponse(vehicle);
    }

    @Override
    public VehicleResponse getVehicleById(Long companyId, Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));

        if (!vehicle.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return mapToResponse(vehicle);
    }

    @Override
    public Page<VehicleResponse> getAllVehicles(Long companyId, Long vehicleTypeId, Long driverUserId, Pageable pageable) {
        // Will implement specification based query for filtering
        return vehicleRepository.findByCompanyId(companyId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    public void deleteVehicle(Long companyId, Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));

        if (!vehicle.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        vehicleRepository.delete(vehicle);
    }
    
    private VehicleResponse mapToResponse(Vehicle vehicle) {
        return VehicleResponse.builder()
                .id(vehicle.getId())
                .vehicleTypeId(vehicle.getVehicleType().getId())
                .licensePlate(vehicle.getLicensePlate())
                .frontAxleLimitKg(vehicle.getFrontAxleLimitKg())
                .rearAxleLimitKg(vehicle.getRearAxleLimitKg())
                .companyId(vehicle.getCompany().getId())
                .driverUserId(vehicle.getDriver() != null ? vehicle.getDriver().getId() : null)
                .build();
    }
}
