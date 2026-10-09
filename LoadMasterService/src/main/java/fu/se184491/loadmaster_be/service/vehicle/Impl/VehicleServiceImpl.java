package fu.se184491.loadmaster_be.service.vehicle.Impl;

import fu.se184491.loadmaster_be.constant.account.UserRole;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleRequest;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
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

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final UserRepository userRepository;

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

    private VehicleResponse toVehicleResponse(
            Vehicle vehicle
    ) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getName(),
                vehicle.getLicensePlate(),
                vehicle.getVehicleType().getId(),
                vehicle.getVehicleType().getName(),
                vehicle.getCompany().getId(),
                vehicle.getCompany().getCompanyName()
        );
    }
}
