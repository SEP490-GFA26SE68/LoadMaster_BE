package fu.se184491.loadmaster_be.service.vehicle.Impl;


import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final TripRepository tripRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public VehicleResponse createVehicle(
            CreateVehicleRequest request
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

        String licensePlate =
                request.licensePlate()
                        .trim()
                        .toUpperCase();

        if (
                vehicleRepository
                        .existsByLicensePlateIgnoreCase(licensePlate)
        ) {
            throw new AppException(
                    ErrorCode.LICENSE_PLATE_ALREADY_EXISTS
            );
        }

        VehicleType vehicleType =
                vehicleTypeRepository
                        .findByIdAndCompanyId(
                                request.vehicleTypeId(),
                                companyId
                        )
                        .orElseThrow(() ->
                                new AppException(
                                        ErrorCode.VEHICLE_TYPE_NOT_FOUND
                                )
                        );



        Vehicle vehicle =
                Vehicle.builder()
                        .company(
                                currentUser.getCompany()
                        )
                        .vehicleType(vehicleType)
                        .name(
                                request.name().trim()
                        )
                        .licensePlate(
                                request
                                        .licensePlate()
                                        .trim()
                                        .toUpperCase()
                        )
                        .build();

        Vehicle saved =
                vehicleRepository.save(vehicle);

        return toVehicleResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> getVehicles(
            int page,
            int size,
            String search,
            Long vehicleTypeId
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

        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.ASC,
                                "name"
                        )
                );

        Specification<Vehicle> spec =
                Specification.unrestricted();

        spec = spec.and(
                (root, query, cb) ->
                        cb.equal(
                                root.get("company").get("id"),
                                companyId
                        )
        );

        if (search != null && !search.isBlank()) {
            String keyword =
                    "%" + search.trim().toLowerCase() + "%";

            spec = spec.and(
                    (root, query, cb) ->
                            cb.or(
                                    cb.like(
                                            cb.lower(root.get("name")),
                                            keyword
                                    ),
                                    cb.like(
                                            cb.lower(root.get("licensePlate")),
                                            keyword
                                    )
                            )
            );
        }

        if (vehicleTypeId != null) {
            spec = spec.and(
                    (root, query, cb) ->
                            cb.equal(
                                    root.get("vehicleType").get("id"),
                                    vehicleTypeId
                            )
            );
        }

        Page<VehicleResponse> result =
                vehicleRepository
                        .findAll(spec, pageable)
                        .map(this::toVehicleResponse);

        return PageResponse.of(
                "Vehicles retrieved successfully",
                result
        );
    }

    @Override
    @Transactional(readOnly = true)
    public VehicleResponse getVehicleById(Long vehicleId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        Vehicle vehicle = vehicleRepository
                .findByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.VEHICLE_NOT_FOUND)
                );

        return toVehicleResponse(vehicle);
    }

    @Override
    @Transactional(readOnly = true)
    public TripVehicleConfigResponse getVehicleConfig(Long vehicleId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        Vehicle vehicle = vehicleRepository
                .findByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.VEHICLE_NOT_FOUND)
                );

        VehicleType type = vehicle.getVehicleType();

        List<TripVehicleConfigResponse.Obstacle> obstacles =
                type.getObstacles().stream()
                        .map(obstacle -> new TripVehicleConfigResponse.Obstacle(
                                obstacle.getId(),
                                obstacle.getName(),
                                obstacle.getType().name(),
                                obstacle.getX(),
                                obstacle.getY(),
                                obstacle.getZ(),
                                obstacle.getLength(),
                                obstacle.getWidth(),
                                obstacle.getHeight(),
                                obstacle.getLoadBearing()
                        ))
                        .toList();

        return new TripVehicleConfigResponse(
                vehicle.getId(),
                vehicle.getName(),
                vehicle.getLicensePlate(),

                type.getId(),
                type.getName(),

                type.getInnerLength(),
                type.getInnerWidth(),
                type.getInnerHeight(),

                type.getDoorWidth(),
                type.getDoorHeight(),

                type.getMaxPayloadKg(),
                type.getFrontAxleLimitKg(),
                type.getRearAxleLimitKg(),
                type.getMaxCogOffsetRatio(),

                type.getFrontAxleX(),
                type.getRearAxleX(),
                type.getEmptyFrontAxleLoadKg(),
                type.getEmptyRearAxleLoadKg(),

                obstacles
        );
    }

    private VehicleResponse toVehicleResponse(Vehicle vehicle) {
        return VehicleResponse.builder()
                .id(vehicle.getId())
                .name(vehicle.getName())
                .licensePlate(vehicle.getLicensePlate())
                .vehicleTypeId(vehicle.getVehicleType().getId())
                .vehicleTypeName(vehicle.getVehicleType().getName())
                .companyId(vehicle.getCompany().getId())
                .companyName(vehicle.getCompany().getCompanyName())
                .status(resolveVehicleStatus(vehicle))
                .build();
    }

    private VehicleStatus resolveVehicleStatus(Vehicle vehicle) {

        VehicleStatus storedStatus = vehicle.getStatus();

        if (storedStatus == VehicleStatus.RETIRED) {
            return VehicleStatus.RETIRED;
        }

        if (storedStatus == VehicleStatus.MAINTENANCE) {
            return VehicleStatus.MAINTENANCE;
        }

        boolean inTrip = tripRepository.existsByVehicleIdAndStatusIn(
                vehicle.getId(),
                List.of(
                        TripStatus.LOADING,
                        TripStatus.IN_TRANSIT
                )
        );

        return inTrip
                ? VehicleStatus.IN_TRIP
                : VehicleStatus.AVAILABLE;
    }
}
