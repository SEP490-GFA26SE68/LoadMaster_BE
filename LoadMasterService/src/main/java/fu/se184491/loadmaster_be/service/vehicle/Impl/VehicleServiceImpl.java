package fu.se184491.loadmaster_be.service.vehicle.Impl;


import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.UpdateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleDeleteResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final TripRepository tripRepository;
    private final CurrentUserService currentUserService;
    private final LoadPlanRepository loadPlanRepository;

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

    /** Chuyến chưa rời kho: phương án của chúng phụ thuộc thùng xe. */
    private static final List<TripStatus> PLANNING = List.of(TripStatus.DRAFT, TripStatus.PLANNED);
    /** Chuyến đang dùng xe ở kho hoặc ngoài đường. */
    private static final List<TripStatus> RUNNING = List.of(TripStatus.LOADING, TripStatus.IN_TRANSIT);
    /** Chuyến chưa kết thúc. */
    private static final List<TripStatus> UNFINISHED =
            List.of(TripStatus.DRAFT, TripStatus.PLANNED, TripStatus.LOADING, TripStatus.IN_TRANSIT);

    // PUT /api/vehicles/{id}
    @Override
    @Transactional
    public VehicleResponse updateVehicle(Long vehicleId, UpdateVehicleRequest request) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Vehicle vehicle = ownVehicle(vehicleId, companyId);
        if (vehicle.getStatus() == VehicleStatus.RETIRED) {
            throw new AppException(ErrorCode.VEHICLE_RETIRED);
        }

        String licensePlate = request.licensePlate().trim().toUpperCase();
        if (!licensePlate.equalsIgnoreCase(vehicle.getLicensePlate())) {
            vehicleRepository.findByLicensePlateIgnoreCase(licensePlate)
                    .filter(other -> !other.getId().equals(vehicle.getId()))
                    .ifPresent(other -> {
                        throw new AppException(ErrorCode.LICENSE_PLATE_ALREADY_EXISTS);
                    });
        }

        VehicleType currentType = vehicle.getVehicleType();
        if (!currentType.getId().equals(request.vehicleTypeId())) {
            if (tripRepository.existsByVehicleIdAndStatusIn(vehicleId, RUNNING)) {
                throw new AppException(ErrorCode.VEHICLE_BUSY);
            }
            VehicleType nextType = vehicleTypeRepository
                    .findByIdAndCompanyId(request.vehicleTypeId(), companyId)
                    .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_TYPE_NOT_FOUND));
            vehicle.setVehicleType(nextType);
            // Thùng xe là đầu vào tối ưu của mọi chuyến chưa rời kho dùng xe này
            tripRepository.findByVehicleIdAndStatusIn(vehicleId, PLANNING)
                    .forEach(trip -> loadPlanRepository.markStaleByTripId(trip.getId()));
        }

        vehicle.setName(request.name().trim());
        vehicle.setLicensePlate(licensePlate);
        return toVehicleResponse(vehicleRepository.save(vehicle));
    }

    // DELETE /api/vehicles/{id}
    @Override
    @Transactional
    public VehicleDeleteResponse deleteVehicle(Long vehicleId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Vehicle vehicle = ownVehicle(vehicleId, companyId);
        if (tripRepository.existsByVehicleIdAndStatusIn(vehicleId, UNFINISHED)) {
            throw new AppException(ErrorCode.VEHICLE_IN_USE);
        }
        // Chuyến đã giao / đã huỷ còn trỏ tới xe: giữ bản ghi cho lịch sử, xe ngừng sử dụng
        if (tripRepository.existsByVehicleId(vehicleId)) {
            vehicle.setStatus(VehicleStatus.RETIRED);
            vehicle.setMaintenanceNote(null);
            vehicle.setMaintenanceSince(null);
            vehicleRepository.save(vehicle);
            return new VehicleDeleteResponse(vehicleId, VehicleDeleteResponse.RETIRED);
        }
        vehicleRepository.delete(vehicle);
        return new VehicleDeleteResponse(vehicleId, VehicleDeleteResponse.DELETED);
    }

    // POST /api/vehicles/{id}/maintenance
    @Override
    @Transactional
    public VehicleResponse startMaintenance(Long vehicleId, String note) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Vehicle vehicle = ownVehicle(vehicleId, companyId);
        if (vehicle.getStatus() == VehicleStatus.RETIRED) {
            throw new AppException(ErrorCode.VEHICLE_RETIRED);
        }
        if (tripRepository.existsByVehicleIdAndStatusIn(vehicleId, RUNNING)) {
            throw new AppException(ErrorCode.VEHICLE_BUSY);
        }
        String trimmed = note == null ? "" : note.trim();
        vehicle.setMaintenanceNote(trimmed.isEmpty() ? null : trimmed);
        if (vehicle.getStatus() != VehicleStatus.MAINTENANCE) {
            vehicle.setMaintenanceSince(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        return toVehicleResponse(vehicleRepository.save(vehicle));
    }

    // DELETE /api/vehicles/{id}/maintenance
    @Override
    @Transactional
    public VehicleResponse endMaintenance(Long vehicleId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Vehicle vehicle = ownVehicle(vehicleId, companyId);
        if (vehicle.getStatus() == VehicleStatus.MAINTENANCE) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
            vehicle.setMaintenanceNote(null);
            vehicle.setMaintenanceSince(null);
            vehicleRepository.save(vehicle);
        }
        return toVehicleResponse(vehicle);
    }

    private Vehicle ownVehicle(Long vehicleId, Long companyId) {
        return vehicleRepository
                .findByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));
    }

    private VehicleResponse toVehicleResponse(Vehicle vehicle) {
        boolean inMaintenance = vehicle.getStatus() == VehicleStatus.MAINTENANCE;
        Trip currentTrip = vehicle.getStatus() == VehicleStatus.RETIRED || inMaintenance
                ? null
                : tripRepository.findByVehicleIdAndStatusIn(vehicle.getId(), RUNNING).stream().findFirst().orElse(null);
        return VehicleResponse.builder()
                .maintenanceNote(inMaintenance ? vehicle.getMaintenanceNote() : null)
                .maintenanceSince(inMaintenance ? vehicle.getMaintenanceSince() : null)
                .currentTripId(currentTrip == null ? null : currentTrip.getId())
                .currentTripCode(currentTrip == null ? null : currentTrip.getTripCode())
                .currentTripStatus(currentTrip == null ? null : currentTrip.getStatus())
                .id(vehicle.getId())
                .name(vehicle.getName())
                .licensePlate(vehicle.getLicensePlate())
                .vehicleTypeId(vehicle.getVehicleType().getId())
                .vehicleTypeName(vehicle.getVehicleType().getName())
                .companyId(vehicle.getCompany().getId())
                .companyName(vehicle.getCompany().getCompanyName())
                .status(resolveVehicleStatus(vehicle, currentTrip))
                .build();
    }

    private static VehicleStatus resolveVehicleStatus(Vehicle vehicle, Trip currentTrip) {
        VehicleStatus storedStatus = vehicle.getStatus();
        if (storedStatus == VehicleStatus.RETIRED || storedStatus == VehicleStatus.MAINTENANCE) {
            return storedStatus;
        }
        return currentTrip != null ? VehicleStatus.IN_TRIP : VehicleStatus.AVAILABLE;
    }
}
