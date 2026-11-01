package fu.se184491.loadmaster_be.service.vehicle.Impl;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleTypeObstacleRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.request.vehicle.VehicleTypeRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeObstacleResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleTypeResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleTypeObstacle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.VehicleTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class VehicleTypeServiceImpl implements VehicleTypeService {

    private final VehicleTypeRepository vehicleTypeRepository;
    // We would need CompanyRepository here to get company reference, simulating with EntityManager or proxy
    // Assuming companyId is valid from the context, we can use a proxy reference to avoid DB hit for company lookup
    private final jakarta.persistence.EntityManager entityManager;
    private final CurrentUserService currentUserService;
    private final fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository vehicleRepository;


    @Transactional
    public VehicleTypeResponse createVehicleType(
            CreateVehicleTypeRequest request
    ) {
        User currentUser =
                currentUserService.getCurrentUser();


        // Kiểm tra kích thước cửa khoang hàng (cm)
        Integer doorWidth = request.doorWidth();
        Integer doorHeight = request.doorHeight();

        // Phải cung cấp cả hai thông số hoặc không cung cấp thông số nào
        if ((doorWidth == null) != (doorHeight == null)) {
            throw new AppException(
                    ErrorCode.VEHICLE_WIDTH_HIGH
            );
        }

        if (doorWidth != null) {
            if (doorWidth <= 0 || doorHeight <= 0) {
                throw new AppException(
                        ErrorCode.VEHICLE_SIZE
                );
            }

            if (doorWidth > request.innerWidth()) {
                throw new AppException(
                        ErrorCode.WIDTH_RATIO
                );
            }

            if (doorHeight > request.innerHeight()) {
                throw new AppException(
                        ErrorCode.HIGH_RATIO
                );
            }
        }


        if (currentUser.getCompany() == null) {
            throw new AppException(
                    ErrorCode.COMPANY_REQUIRED
            );
        }

        VehicleType vehicleType =
                VehicleType.builder()
                        .company(currentUser.getCompany())
                        .name(request.name())

                        .innerLength(request.innerLength())
                        .innerWidth(request.innerWidth())
                        .innerHeight(request.innerHeight())
                        .doorWidth(request.doorWidth())
                        .doorHeight(request.doorHeight())

                        .maxPayloadKg(request.maxPayloadKg())
                        .frontAxleLimitKg(
                                request.frontAxleLimitKg()
                        )
                        .rearAxleLimitKg(
                                request.rearAxleLimitKg()
                        )
                        .maxCogOffsetRatio(
                                request.maxCogOffsetRatio() != null
                                        ? request.maxCogOffsetRatio()
                                        : new BigDecimal("0.15")
                        )
                        .hazardousCapable(
                                Boolean.TRUE.equals(
                                        request.hazardousCapable()
                                )
                        )
                        .build();

        if (request.obstacles() != null) {
            for (
                    CreateVehicleTypeObstacleRequest obstacleRequest
                    : request.obstacles()
            ) {
                VehicleTypeObstacle obstacle =
                        VehicleTypeObstacle.builder()
                                .name(
                                        obstacleRequest.name()
                                )
                                .type(
                                        obstacleRequest.type()
                                )
                                .x(obstacleRequest.x())
                                .y(obstacleRequest.y())
                                .z(obstacleRequest.z())
                                .length(
                                        obstacleRequest.length()
                                )
                                .width(
                                        obstacleRequest.width()
                                )
                                .height(
                                        obstacleRequest.height()
                                )
                                .loadBearing(
                                        Boolean.TRUE.equals(
                                                obstacleRequest
                                                        .loadBearing()
                                        )
                                )
                                .build();

                vehicleType.addObstacle(obstacle);
            }
        }

        VehicleType savedVehicleType = vehicleTypeRepository.save(vehicleType);

        return toResponse(savedVehicleType);
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

    @Transactional(readOnly = true)
    public PageResponse<VehicleTypeResponse> getVehicleTypes(
            int page,
            int size,
            String search
    ) {
        User currentUser =
                currentUserService.getCurrentUser();

        if (currentUser.getCompany() == null) {
            throw new AppException(
                    ErrorCode.COMPANY_REQUIRED
            );
        }

        Long companyId =
                currentUser.getCompany().getId();

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.ASC,
                        "name"
                )
        );

        Page<VehicleType> result;

        if (search != null && !search.isBlank()) {
            result =
                    vehicleTypeRepository
                            .findAllByCompanyIdAndNameContainingIgnoreCase(
                                    companyId,
                                    search.trim(),
                                    pageable
                            );
        } else {
            result =
                    vehicleTypeRepository
                            .findAllByCompanyId(
                                    companyId,
                                    pageable
                            );
        }

        Page<VehicleTypeResponse> response =
                result.map(this::toResponse);

        return PageResponse.of(
                "Vehicle types retrieved successfully",
                response
        );
    }

    private VehicleTypeResponse toResponse(
            VehicleType vehicleType
    ) {
        long vehicleCount =
                vehicleRepository.countByVehicleTypeId(
                        vehicleType.getId()
                );
        return VehicleTypeResponse.builder()
                .id(vehicleType.getId())
                .name(vehicleType.getName())

                .innerLength(vehicleType.getInnerLength())
                .innerWidth(vehicleType.getInnerWidth())
                .innerHeight(vehicleType.getInnerHeight())
                .doorWidth(vehicleType.getDoorWidth())
                .doorHeight(vehicleType.getDoorHeight())

                .maxPayloadKg(vehicleType.getMaxPayloadKg())
                .frontAxleLimitKg(vehicleType.getFrontAxleLimitKg())
                .rearAxleLimitKg(vehicleType.getRearAxleLimitKg())
                .maxCogOffsetRatio(vehicleType.getMaxCogOffsetRatio())
                .hazardousCapable(vehicleType.getHazardousCapable())
                .obstacles(
                        vehicleType.getObstacles().stream()
                                .map(obstacle -> VehicleTypeObstacleResponse.builder()
                                        .id(obstacle.getId())
                                        .name(obstacle.getName())
                                        .type(obstacle.getType())
                                        .x(obstacle.getX())
                                        .y(obstacle.getY())
                                        .z(obstacle.getZ())
                                        .length(obstacle.getLength())
                                        .width(obstacle.getWidth())
                                        .height(obstacle.getHeight())
                                        .loadBearing(obstacle.getLoadBearing())
                                        .build()
                                ).toList()
                )
                .vehicleCount(vehicleCount)
                .build();
    }
}
