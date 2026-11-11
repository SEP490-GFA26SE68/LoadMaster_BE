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
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
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
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleTypeServiceImpl implements VehicleTypeService {

    private final VehicleTypeRepository vehicleTypeRepository;
    // We would need CompanyRepository here to get company reference, simulating with EntityManager or proxy
    // Assuming companyId is valid from the context, we can use a proxy reference to avoid DB hit for company lookup
    private final jakarta.persistence.EntityManager entityManager;
    private final CurrentUserService currentUserService;
    private final fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository vehicleRepository;
    private final TripRepository tripRepository;
    private final LoadPlanRepository loadPlanRepository;


    @Transactional
    public VehicleTypeResponse createVehicleType(
            CreateVehicleTypeRequest request
    ) {
        User currentUser =
                currentUserService.getCurrentUser();


        validateDoor(request);


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
                vehicleType.addObstacle(toObstacle(obstacleRequest));
            }
        }

        VehicleType savedVehicleType = vehicleTypeRepository.save(vehicleType);

        return toResponse(savedVehicleType);
    }

    /** Cửa khoang hàng (cm): cả hai số hoặc không số nào, dương, không vượt lòng thùng. */
    private static void validateDoor(CreateVehicleTypeRequest request) {
        Integer doorWidth = request.doorWidth();
        Integer doorHeight = request.doorHeight();
        if ((doorWidth == null) != (doorHeight == null)) {
            throw new AppException(ErrorCode.VEHICLE_WIDTH_HIGH);
        }
        if (doorWidth != null) {
            if (doorWidth <= 0 || doorHeight <= 0) {
                throw new AppException(ErrorCode.VEHICLE_SIZE);
            }
            if (doorWidth > request.innerWidth()) {
                throw new AppException(ErrorCode.WIDTH_RATIO);
            }
            if (doorHeight > request.innerHeight()) {
                throw new AppException(ErrorCode.HIGH_RATIO);
            }
        }
    }

    private static VehicleTypeObstacle toObstacle(CreateVehicleTypeObstacleRequest obstacleRequest) {
        return
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
    }

    /**
     * Sửa loại xe: tên, lòng thùng, cửa, tải trọng, giới hạn trục, độ lệch trọng tâm thay hẳn bằng số gửi lên; độ lệch trọng tâm,
     * hàng nguy hiểm và danh sách vật cản vắng thì giữ như cũ. Vị trí trục và tải rỗng không sửa ở đây. Đổi phần nào ngoài tên thì
     * phương án của chuyến chưa rời kho dùng xe thuộc loại này thành lỗi thời.
     */
    // PUT /api/vehicle-types/{id}
    @Override
    @Transactional
    public VehicleTypeResponse updateVehicleType(Long id, CreateVehicleTypeRequest request) {
        Long companyId = currentUserService.getCurrentCompanyId();
        VehicleType type = vehicleTypeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
        validateDoor(request);

        String before = specOf(type);
        type.setName(request.name().trim());
        type.setInnerLength(request.innerLength());
        type.setInnerWidth(request.innerWidth());
        type.setInnerHeight(request.innerHeight());
        type.setDoorWidth(request.doorWidth());
        type.setDoorHeight(request.doorHeight());
        type.setMaxPayloadKg(request.maxPayloadKg());
        type.setFrontAxleLimitKg(request.frontAxleLimitKg());
        type.setRearAxleLimitKg(request.rearAxleLimitKg());
        if (request.maxCogOffsetRatio() != null) {
            type.setMaxCogOffsetRatio(request.maxCogOffsetRatio());
        }
        if (request.hazardousCapable() != null) {
            type.setHazardousCapable(request.hazardousCapable());
        }
        if (request.obstacles() != null) {
            for (VehicleTypeObstacle obstacle : List.copyOf(type.getObstacles())) {
                type.removeObstacle(obstacle);
            }
            request.obstacles().forEach(obstacle -> type.addObstacle(toObstacle(obstacle)));
        }

        if (!before.equals(specOf(type))) {
            tripRepository.findByVehicle_VehicleType_IdAndStatusIn(id, List.of(TripStatus.DRAFT, TripStatus.PLANNED))
                    .forEach(trip -> loadPlanRepository.markStaleByTripId(trip.getId()));
        }
        return toResponse(vehicleTypeRepository.save(type));
    }

    /** Mọi số của loại xe mà phương án xếp hàng dùng (không gồm tên), để biết lần sửa có đổi đầu vào tối ưu không. */
    private static String specOf(VehicleType type) {
        StringBuilder spec = new StringBuilder()
                .append(type.getInnerLength()).append('|')
                .append(type.getInnerWidth()).append('|')
                .append(type.getInnerHeight()).append('|')
                .append(type.getDoorWidth()).append('|')
                .append(type.getDoorHeight()).append('|')
                .append(plain(type.getMaxPayloadKg())).append('|')
                .append(plain(type.getFrontAxleLimitKg())).append('|')
                .append(plain(type.getRearAxleLimitKg())).append('|')
                .append(plain(type.getMaxCogOffsetRatio())).append('|')
                .append(type.getHazardousCapable());
        for (VehicleTypeObstacle obstacle : type.getObstacles()) {
            spec.append('|').append(obstacle.getType())
                    .append(',').append(plain(obstacle.getX()))
                    .append(',').append(plain(obstacle.getY()))
                    .append(',').append(plain(obstacle.getZ()))
                    .append(',').append(obstacle.getLength())
                    .append(',').append(obstacle.getWidth())
                    .append(',').append(obstacle.getHeight())
                    .append(',').append(obstacle.getLoadBearing());
        }
        return spec.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }




    @Override
    @Transactional
    // DELETE /api/vehicle-types/{id}
    public void deleteVehicleType(Long id) {
        Long companyId = currentUserService.getCurrentCompanyId();
        VehicleType type = vehicleTypeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
        // Kể cả xe đã ngừng sử dụng: bản ghi xe vẫn trỏ tới loại
        if (vehicleRepository.countByVehicleTypeId(id) > 0) {
            throw new AppException(ErrorCode.VEHICLE_TYPE_IN_USE);
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
