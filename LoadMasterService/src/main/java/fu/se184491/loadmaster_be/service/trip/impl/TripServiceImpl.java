
package fu.se184491.loadmaster_be.service.trip.impl;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.request.trip.DriverResponse;
import fu.se184491.loadmaster_be.dto.request.trip.TripCreateRequest;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripStopResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.service.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final DeliveryStopRepository deliveryStopRepository;
    @Transactional
    public TripResponse createTrip(TripCreateRequest request) {

        // 1. Lấy điều phối viên và công ty hiện tại.
        User creator = currentUserService.getCurrentUser();

        if (creator.getCompany() == null) {
            throw new AppException(
                    ErrorCode.USER_COMPANY_NOT_FOUND
            );
        }

        Long companyId = creator.getCompany().getId();

        // 2. Kiểm tra xe.
        Vehicle vehicle = vehicleRepository
                .findById(request.vehicleId())
                .orElseThrow(() ->
                        new AppException(
                                ErrorCode.VEHICLE_NOT_FOUND
                        )
                );

        if (!companyId.equals(vehicle.getCompany().getId())) {
            throw new AppException(
                    ErrorCode.VEHICLE_NOT_FOUND
            );
        }

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new AppException(
                    ErrorCode.VEHICLE_NOT_AVAILABLE
            );
        }

        // 3. Kiểm tra tài xế (không bắt buộc).
        User driver = null;

        if (request.driverId() != null) {
            driver = userRepository
                    .findById(request.driverId())
                    .orElseThrow(() ->
                            new AppException(
                                    ErrorCode.USER_NOT_FOUND
                            )
                    );

            if (driver.getCompany() == null
                    || !companyId.equals(driver.getCompany().getId())) {
                throw new AppException(
                        ErrorCode.DRIVER_COMPANY_MISMATCH
                );
            }

            if (driver.getUserRoleType() != UserRole.DRIVER) {
                throw new AppException(
                        ErrorCode.USER_IS_NOT_DRIVER
                );
            }

            if (driver.getStatus() != UserStatus.ACTIVE) {
                throw new AppException(
                        ErrorCode.DRIVER_NOT_AVAILABLE
                );
            }
        }

        // 4. Sinh mã chuyến.
        String tripCode = "TRIP-"
                + UUID.randomUUID()
                .toString()
                .substring(0, 12)
                .toUpperCase();

        // 5. Tạo chuyến ở trạng thái DRAFT.
        Trip trip = Trip.builder()
                .company(creator.getCompany())
                .vehicle(vehicle)
                .driver(driver)
                .createdByDispatcher(creator)
                .tripCode(tripCode)
                .tripName(request.tripName().trim())
                .departureTime(request.departureTime())
                .originName(request.originName().trim())
                .originAddress(request.originAddress())
                .originLatitude(request.originLatitude())
                .originLongitude(request.originLongitude())
                .status(TripStatus.DRAFT)
                .createdAt(Instant.now())
                .build();

        Trip savedTrip = tripRepository.save(trip);
        return toResponse(savedTrip);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DriverResponse> getDrivers() {
        User currentUser = currentUserService.getCurrentUser();

        if (currentUser.getCompany() == null) {
            throw new AppException(
                    ErrorCode.USER_COMPANY_NOT_FOUND
            );
        }

        Long companyId = currentUser.getCompany().getId();

        List<User> drivers =
                userRepository.findByCompanyIdAndUserRoleTypeAndStatus(
                        companyId,
                        UserRole.DRIVER,
                        UserStatus.ACTIVE
                );

        return drivers.stream()
                .map(driver -> new DriverResponse(
                        driver.getId(),
                        driver.getFullName()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TripResponse getTripById(Long tripId) {

        // 1. Xác định công ty từ người đăng nhập.
        Long companyId = currentUserService.getCurrentCompanyId();

        // 2. Chỉ tìm chuyến thuộc công ty hiện tại.
        Trip trip = tripRepository
                .findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.TRIP_NOT_FOUND)
                );

        // 3. Trả DTO, không trả trực tiếp JPA Entity.
        return toResponse(trip);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TripResponse> getTrips(
            Pageable pageable,
            String search,
            TripStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Long vehicleId,
            Long driverId,
            boolean unassignedDriver
    ) {
        User currentUser = currentUserService.getCurrentUser();

        if (currentUser.getCompany() == null) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }

        Long companyId = currentUser.getCompany().getId();

        Specification<Trip> spec = (root, query, cb) ->
                cb.equal(root.get("company").get("id"), companyId);

        if (search != null && !search.isBlank()) {
            String keyword = "%" + search.trim().toLowerCase() + "%";

            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("tripName")), keyword),
                    cb.like(cb.lower(root.get("tripCode")), keyword)
            ));
        }

        if (status != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("status"), status));
        }

        if (fromDate != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(
                            root.get("departureTime"),
                            fromDate.atStartOfDay()
                    ));
        }

        if (toDate != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThan(
                            root.get("departureTime"),
                            toDate.plusDays(1).atStartOfDay()
                    ));
        }

        if (vehicleId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("vehicle").get("id"), vehicleId));
        }

        if (unassignedDriver) {
            spec = spec.and((root, query, cb) ->
                    cb.isNull(root.get("driver")));
        } else if (driverId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("driver").get("id"), driverId));
        }

        return tripRepository
                .findAll(spec, pageable)
                .map(this::toResponse);
    }


    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getTripDetail(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();

        // Kiểm tra chuyến thuộc công ty đang đăng nhập.
        Trip trip = tripRepository
                .findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.TRIP_NOT_FOUND)
                );

        // Chỉ truy vấn điểm giao sau khi xác nhận quyền xem chuyến.
        List<TripStopResponse> stops = deliveryStopRepository
                .findByTripIdOrderByStopSequenceAsc(tripId)
                .stream()
                .map(TripStopResponse::fromEntity)
                .toList();

        return TripDetailResponse.builder()
                .trip(toResponse(trip))
                .stops(stops)
                .build();
    }


    private TripResponse toResponse(Trip trip) {
        return TripResponse.builder()
                .id(trip.getId())
                .tripCode(trip.getTripCode())
                .tripName(trip.getTripName())
                .companyId(trip.getCompany() != null ? trip.getCompany().getId() : null)
                .vehicleId(trip.getVehicle().getId())
                .vehicleName(trip.getVehicle().getName())
                .licensePlate(trip.getVehicle().getLicensePlate())
                .driverId(trip.getDriver() != null ? trip.getDriver().getId() : null)
                .driverName(trip.getDriver() != null ? trip.getDriver().getFullName() : null)
                .createdByDispatcherId(trip.getCreatedByDispatcher().getId())
                .departureTime(trip.getDepartureTime())
                .originName(trip.getOriginName())
                .originAddress(trip.getOriginAddress())
                .originLatitude(trip.getOriginLatitude())
                .originLongitude(trip.getOriginLongitude())
                .status(trip.getStatus())
                .createdAt(trip.getCreatedAt())
                .build();
    }


}
