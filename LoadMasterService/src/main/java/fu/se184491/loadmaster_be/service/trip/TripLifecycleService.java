package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.dto.request.trip.TripUpdateRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sửa, huỷ chuyến và gỡ yêu cầu giao khỏi chuyến (điều phối viên, trong công ty của mình).
 */
@Service
@RequiredArgsConstructor
public class TripLifecycleService {

    private static final Set<TripStatus> EDITABLE = EnumSet.of(TripStatus.DRAFT, TripStatus.PLANNED);
    private static final Set<TripStatus> CANCELLABLE =
            EnumSet.of(TripStatus.DRAFT, TripStatus.PLANNED, TripStatus.LOADING);
    private static final Set<TripStatus> VEHICLE_BUSY = EnumSet.of(TripStatus.LOADING, TripStatus.IN_TRANSIT);
    private static final String PICKUP_CANCEL_REASON = "Chuyến đã huỷ";
    /** Sự cố trên đường chưa xử lý: cho phép huỷ chuyến Đang vận chuyển. */
    private static final Set<TripExceptionStatus> UNRESOLVED_EXCEPTIONS =
            EnumSet.of(TripExceptionStatus.OPEN, TripExceptionStatus.ESCALATED);

    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final CompanyWarehouseRepository companyWarehouseRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;
    private final TripStatusService tripStatusService;
    private final LoadPlanRepository loadPlanRepository;
    private final TripPackageResolver tripPackageResolver;
    private final TripExceptionRepository tripExceptionRepository;
    private final PickupRequestRepository pickupRequestRepository;
    private final CargoPackageRepository cargoPackageRepository;

    /**
     * Sửa tên, giờ xuất phát, xe, tài xế, kho xuất phát của chuyến Nháp / Đã lập kế hoạch.
     * Đổi giờ xuất phát hoặc kho xuất phát làm tuyến cũ sai: bỏ tuyến, chuyến về Nháp.
     */
    @Transactional
    public void updateTrip(Long tripId, TripUpdateRequest request) {
        User actor = currentUserService.getCurrentUser();
        Long companyId = companyOf(actor);
        Trip trip = requireTripForUpdate(tripId, companyId);
        if (!EDITABLE.contains(trip.getStatus())) {
            throw new AppException(ErrorCode.TRIP_NOT_EDITABLE);
        }

        Map<String, Object> before = new HashMap<>();
        Map<String, Object> after = new HashMap<>();
        boolean routeStale = false;
        boolean vehicleChanged = false;

        if (request.tripName() != null) {
            String name = request.tripName().trim();
            if (name.isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Tên chuyến không được để trống");
            }
            if (!name.equals(trip.getTripName())) {
                record(before, after, "tripName", trip.getTripName(), name);
                trip.setTripName(name);
            }
        }

        if (request.departureTime() != null
                && !request.departureTime().equals(trip.getDepartureTime())) {
            record(before, after, "departureTime", trip.getDepartureTime(), request.departureTime());
            trip.setDepartureTime(request.departureTime());
            routeStale = true;
        }

        if (request.originWarehouseId() != null) {
            Long currentWarehouseId = trip.getOriginWarehouse() != null ? trip.getOriginWarehouse().getId() : null;
            if (!request.originWarehouseId().equals(currentWarehouseId)) {
                CompanyWarehouse warehouse = requireUsableWarehouse(request.originWarehouseId(), companyId);
                record(before, after, "originWarehouseId", currentWarehouseId, warehouse.getId());
                trip.setOriginWarehouse(warehouse);
                trip.setOriginName(warehouse.getWarehouseName());
                trip.setOriginAddress(warehouse.getAddress());
                trip.setOriginLatitude(warehouse.getLatitude());
                trip.setOriginLongitude(warehouse.getLongitude());
                routeStale = true;
            }
        }

        if (request.vehicleId() != null && !request.vehicleId().equals(trip.getVehicle().getId())) {
            Vehicle vehicle = requireUsableVehicle(request.vehicleId(), companyId);
            record(before, after, "vehicleId", trip.getVehicle().getId(), vehicle.getId());
            requireCarriesCargo(vehicle, trip.getId());
            trip.setVehicle(vehicle);
            vehicleChanged = true;
        }

        if (Boolean.TRUE.equals(request.unassignDriver())) {
            if (request.driverId() != null) {
                throw new AppException(ErrorCode.INVALID_INPUT,
                        "Không gửi cùng lúc driverId và unassignDriver");
            }
            if (trip.getDriver() != null) {
                record(before, after, "driverId", trip.getDriver().getId(), null);
                trip.setDriver(null);
            }
        } else if (request.driverId() != null) {
            Long currentDriverId = trip.getDriver() != null ? trip.getDriver().getId() : null;
            if (!request.driverId().equals(currentDriverId)) {
                User driver = requireUsableDriver(request.driverId(), companyId);
                record(before, after, "driverId", currentDriverId, driver.getId());
                trip.setDriver(driver);
            }
        }

        if (after.isEmpty()) {
            return;
        }
        tripRepository.save(trip);
        // Pha của chuyến lúc sửa (giống nhau ở hai bên nên không phải trường đã đổi): chuông của tài xế biết
        // chuyến đã ở kho hay chưa khi đổi tài xế
        before.put("tripStatus", trip.getStatus().name());
        after.put("tripStatus", trip.getStatus().name());
        audit("TRIP_UPDATED", trip, actor, before, after);
        if (vehicleChanged) {
            // Phương án tính cho xe cũ: lỗi thời và thôi là bản duyệt; điều phối tối ưu lại rồi duyệt.
            loadPlanRepository.markStaleByTripId(trip.getId());
        }
        if (routeStale) {
            tripStatusService.invalidateRoute(trip, actor.getId());
        }
    }

    /**
     * Huỷ chuyến Nháp / Đã lập kế hoạch / Đang xếp hàng. Yêu cầu giao về Chờ xử lý và rời
     * chuyến, kiện về Chờ xử lý; phiên xếp đang mở bị dừng. Điểm giao ở lại làm lịch sử.
     * Chuyến Đang vận chuyển chỉ huỷ được khi còn sự cố trên đường chưa xử lý ({@link #cancelInTransit}).
     */
    @Transactional
    public void cancelTrip(Long tripId, String reason) {
        User actor = currentUserService.getCurrentUser();
        Long companyId = companyOf(actor);
        Trip trip = requireTripForUpdate(tripId, companyId);
        boolean inTransit = trip.getStatus() == TripStatus.IN_TRANSIT;
        if (!inTransit && !CANCELLABLE.contains(trip.getStatus())) {
            throw new AppException(ErrorCode.TRIP_NOT_CANCELLABLE);
        }
        if (inTransit && !tripExceptionRepository.existsByTripIdAndStatusIn(tripId, UNRESOLVED_EXCEPTIONS)) {
            throw new AppException(ErrorCode.TRIP_CANCEL_NEEDS_OPEN_EXCEPTION);
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Vui lòng nhập lý do huỷ chuyến");
        }
        TripStatus previousStatus = trip.getStatus();
        trip.setCancelReason(trimmedReason);
        trip.setCancelledFromStatus(previousStatus);
        if (inTransit) {
            cancelInTransit(trip, actor, trimmedReason);
            return;
        }

        int loadedCount = 0;
        int requirementCount = 0;
        for (DeliveryRequirement requirement : deliveryRequirementRepository.findByTripId(tripId)) {
            for (CargoPackage cargoPackage : requirement.getPackages()) {
                if (cargoPackage.getStatus() == PackageStatus.LOADED) {
                    loadedCount++;
                }
                if (cargoPackage.getStatus() == PackageStatus.PLANNED
                        || cargoPackage.getStatus() == PackageStatus.LOADED) {
                    cargoPackage.setStatus(PackageStatus.PENDING);
                }
            }
            requirement.setTrip(null);
            requirement.setStatus(DeliveryRequirementStatus.PENDING);
            requirementCount++;
        }

        for (LoadingExecution execution : loadingExecutionsOf(tripId)) {
            if (execution.getStatus() == LoadingExecutionStatus.IN_PROGRESS) {
                execution.setStatus(LoadingExecutionStatus.ABORTED);
            }
        }

        tripStatusService.transitionTo(tripId, TripStatus.CANCELLED, actor.getId());

        Map<String, Object> after = new HashMap<>();
        after.put("status", TripStatus.CANCELLED.name());
        after.put("reason", trimmedReason);
        after.put("requirementsReleased", requirementCount);
        after.put("loadedPackages", loadedCount);
        if (trip.getDriver() != null) {
            after.put("driverId", trip.getDriver().getId());
        }
        audit("TRIP_CANCELLED", trip, actor, Map.of("status", previousStatus.name()), after);
    }

    /**
     * Huỷ chuyến Đang vận chuyển: kiện của mọi điểm chưa hoàn tất (còn trên xe, hoặc đã dỡ mà điểm chưa hoàn tất) thành
     * Hoàn trả và vẫn gắn với chuyến; kiện nhận dọc đường đã lên xe ở điểm nhận chưa hoàn tất cũng thành Hoàn trả; yêu cầu giao
     * giữ chuyến nên đọc ra là giao thiếu; sự cố chưa xử lý ở lại như đã ghi.
     * Xe hết bận khi chuyến không còn Đang vận chuyển.
     */
    private void cancelInTransit(Trip trip, User actor, String reason) {
        Long tripId = trip.getId();
        Set<Long> returnedIds = new HashSet<>();
        for (DeliveryStop stop : deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(tripId)) {
            if (stop.getStatus() == DeliveryStopStatus.COMPLETED) {
                continue;
            }
            for (CargoPackage cargoPackage : tripPackageResolver.packagesOfStop(stop.getId())) {
                if (cargoPackage.getStatus() == PackageStatus.LOADED
                        || cargoPackage.getStatus() == PackageStatus.DELIVERED) {
                    cargoPackage.setStatus(PackageStatus.RETURNED);
                    returnedIds.add(cargoPackage.getId());
                }
            }
            if (stop.kind() == DeliveryStopType.PICKUP) {
                settlePickupsAtOpenStop(trip, actor, stop, returnedIds);
            }
        }
        trip.setCancelReturnedCount(returnedIds.size());

        tripStatusService.transitionTo(tripId, TripStatus.CANCELLED, actor.getId());

        Map<String, Object> after = new HashMap<>();
        after.put("status", TripStatus.CANCELLED.name());
        after.put("reason", reason);
        after.put("returned", returnedIds.size());
        if (trip.getDriver() != null) {
            after.put("driverId", trip.getDriver().getId());
        }
        audit("TRIP_CANCELLED", trip, actor, Map.of("status", TripStatus.IN_TRANSIT.name()), after);
    }

    /**
     * Yêu cầu nhận còn APPROVED ở điểm nhận chưa hoàn tất: kiện đã lên xe thành Hoàn trả; kiện chưa nhận bị xoá khỏi kho kiện vì
     * hàng chưa từng vào tay. Không kiện nào lên xe thì yêu cầu thành Từ chối "Chuyến đã huỷ".
     */
    private void settlePickupsAtOpenStop(Trip trip, User actor, DeliveryStop stop, Set<Long> returnedIds) {
        for (PickupRequest request : pickupRequestRepository.findByPickupStopId(stop.getId())) {
            if (request.getStatus() != PickupRequestStatus.APPROVED) {
                continue;
            }
            int picked = 0;
            List<CargoPackage> dropped = new ArrayList<>();
            for (PickupRequestPackage line : request.getPackages()) {
                CargoPackage cargoPackage = line.getCargoPackage();
                if (cargoPackage == null) {
                    continue;
                }
                if (line.getPickedAt() != null) {
                    picked++;
                    if (cargoPackage.getStatus() == PackageStatus.LOADED) {
                        cargoPackage.setStatus(PackageStatus.RETURNED);
                        returnedIds.add(cargoPackage.getId());
                    }
                } else {
                    line.setCargoPackage(null);
                    dropped.add(cargoPackage);
                }
            }
            if (picked == 0) {
                // APPROVED -> REJECTED chỉ có ở đây; bảng chuyển trạng thái của yêu cầu nhận không cho lối này
                request.setStatus(PickupRequestStatus.REJECTED);
                request.setRejectReason(PICKUP_CANCEL_REASON);
                request.setRejectedBy(actor);
                request.setRejectedAt(MonitoringTime.now());
            }
            pickupRequestRepository.save(request);
            cargoPackageRepository.deleteAll(dropped);
            if (picked == 0 || !dropped.isEmpty()) {
                auditPickupOnCancel(trip, actor, request, picked == 0, dropped.size());
            }
        }
    }

    private void auditPickupOnCancel(Trip trip, User actor, PickupRequest request, boolean rejected, int droppedCount) {
        Map<String, Object> values = new HashMap<>();
        values.put("pickupId", request.getId());
        values.put("tripId", trip.getId());
        values.put("reason", PICKUP_CANCEL_REASON);
        values.put("droppedPackages", droppedCount);
        values.put("tripCancelled", true);
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(rejected ? "PICKUP_REJECTED" : "PICKUP_PACKAGES_DROPPED")
                .entityName("PickupRequest")
                .entityId(String.valueOf(request.getId()))
                .user(actor)
                .newValues(values)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Gỡ một yêu cầu giao khỏi chuyến Nháp / Đã lập kế hoạch: xoá điểm giao của nó, đánh số
     * lại các điểm còn lại, yêu cầu và kiện về Chờ xử lý, bỏ tuyến (chuyến về Nháp).
     */
    @Transactional
    public void removeRequirementFromTrip(Long tripId, Long requirementId) {
        User actor = currentUserService.getCurrentUser();
        Long companyId = companyOf(actor);
        Trip trip = requireTripForUpdate(tripId, companyId);
        if (!EDITABLE.contains(trip.getStatus())) {
            throw new AppException(ErrorCode.TRIP_NOT_EDITABLE);
        }
        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyIdForUpdate(requirementId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
        if (requirement.getTrip() == null || !tripId.equals(requirement.getTrip().getId())) {
            throw new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_IN_TRIP);
        }
        releaseRequirement(trip, requirement, actor, null);
    }

    /**
     * Yêu cầu giao rời chuyến: xoá điểm giao của nó, đánh số lại các điểm còn lại, yêu cầu và kiện chờ xếp về Chờ xử lý, phương án lỗi
     * thời, bỏ tuyến (chuyến về Nháp). Không kiểm quyền hay pha — nơi gọi kiểm. {@code reason} (tuỳ chọn) ghi vào nhật ký, ví dụ khi kho
     * bỏ kiện cuối cùng của yêu cầu.
     */
    @Transactional
    public void releaseRequirement(Trip trip, DeliveryRequirement requirement, User actor, String reason) {
        Long tripId = trip.getId();
        Long requirementId = requirement.getId();
        List<DeliveryStop> removedStops =
                deliveryStopRepository.findByTripIdAndDeliveryRequirementId(tripId, requirementId);
        for (DeliveryStop stop : removedStops) {
            deliveryStopRepository.deleteRouteLegsTouchingStop(stop.getId());
            deliveryStopRepository.detachPlacementsFromStop(stop.getId());
            deliveryStopRepository.detachOrdersFromStop(stop.getId());
            deliveryStopRepository.detachTripCargoPackagesFromStop(stop.getId());
            deliveryStopRepository.delete(stop);
        }
        deliveryStopRepository.flush();
        resequenceStops(tripId);

        for (CargoPackage cargoPackage : requirement.getPackages()) {
            if (cargoPackage.getStatus() == PackageStatus.PLANNED) {
                cargoPackage.setStatus(PackageStatus.PENDING);
            }
        }
        requirement.setTrip(null);
        requirement.setStatus(DeliveryRequirementStatus.PENDING);

        Map<String, Object> after = new HashMap<>();
        after.put("requirementId", requirementId);
        after.put("removedStops", removedStops.stream().map(DeliveryStop::getId).toList());
        if (reason != null) {
            after.put("reason", reason);
        }
        audit("TRIP_REQUIREMENT_REMOVED", trip, actor, Map.of(), after);
        // Hàng của chuyến vừa bớt: phương án xếp đã có thành lỗi thời.
        loadPlanRepository.markStaleByTripId(tripId);

        tripStatusService.invalidateRoute(trip, actor != null ? actor.getId() : null);
    }

    /** Đánh số lại 1..n theo thứ tự hiện tại; hai lượt để không vướng khoá (trip_id, stop_sequence). */
    private void resequenceStops(Long tripId) {
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
        int offset = stops.stream()
                .map(DeliveryStop::getStopSequence)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        for (int index = 0; index < stops.size(); index++) {
            stops.get(index).setStopSequence(offset + index);
        }
        deliveryStopRepository.flush();
        for (int index = 0; index < stops.size(); index++) {
            stops.get(index).setStopSequence(index + 1);
        }
        deliveryStopRepository.flush();
    }

    private List<LoadingExecution> loadingExecutionsOf(Long tripId) {
        return new ArrayList<>(loadingExecutionRepository.findAllByLoadPlanJobTripId(tripId));
    }

    private Trip requireTripForUpdate(Long tripId, Long companyId) {
        return tripRepository.findByIdAndCompanyIdForUpdate(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }

    private Long companyOf(User user) {
        if (user.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        return user.getCompany().getId();
    }

    private CompanyWarehouse requireUsableWarehouse(Long warehouseId, Long companyId) {
        CompanyWarehouse warehouse = companyWarehouseRepository
                .findByIdAndCompanyId(warehouseId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT, "Kho xuất phát không thuộc công ty"));
        if (!warehouse.isActive()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Kho xuất phát đã ngừng hoạt động");
        }
        if (warehouse.getLatitude() == null || warehouse.getLongitude() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Kho xuất phát chưa có toạ độ");
        }
        return warehouse;
    }

    /** Điều kiện cần trên tổng hàng (không xếp thử): kích thước + cửa, thể tích, tải trọng, tải trục ({@link VehicleCargoFit}). */
    private void requireCarriesCargo(Vehicle vehicle, Long tripId) {
        List<VehicleCargoFit.Issue> issues =
                VehicleCargoFit.issues(vehicle.getVehicleType(), tripPackageResolver.packagesOfTrip(tripId));
        if (!issues.isEmpty()) {
            throw new AppException(ErrorCode.VEHICLE_CANNOT_CARRY_CARGO, issues.get(0).message());
        }
    }

    private Vehicle requireUsableVehicle(Long vehicleId, Long companyId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));
        if (vehicle.getCompany() == null || !companyId.equals(vehicle.getCompany().getId())) {
            throw new AppException(ErrorCode.VEHICLE_NOT_FOUND);
        }
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE
                || tripRepository.existsByVehicleIdAndStatusIn(vehicleId, VEHICLE_BUSY)) {
            throw new AppException(ErrorCode.VEHICLE_NOT_AVAILABLE);
        }
        return vehicle;
    }

    private User requireUsableDriver(Long driverId, Long companyId) {
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (driver.getCompany() == null || !companyId.equals(driver.getCompany().getId())) {
            throw new AppException(ErrorCode.DRIVER_COMPANY_MISMATCH);
        }
        if (driver.getUserRoleType() != UserRole.DRIVER) {
            throw new AppException(ErrorCode.USER_IS_NOT_DRIVER);
        }
        if (driver.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.DRIVER_NOT_AVAILABLE);
        }
        return driver;
    }

    private static void record(
            Map<String, Object> before,
            Map<String, Object> after,
            String field,
            Object oldValue,
            Object newValue
    ) {
        before.put(field, oldValue == null ? "" : oldValue.toString());
        after.put(field, newValue == null ? "" : newValue.toString());
    }

    private void audit(
            String action,
            Trip trip,
            User actor,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(action)
                .entityName("Trip")
                .entityId(trip.getId().toString())
                .user(actor)
                .oldValues(before)
                .newValues(after)
                .createdAt(Instant.now())
                .build());
    }
}
