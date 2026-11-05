package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
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
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import java.math.BigDecimal;
import java.util.Arrays;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
        audit("TRIP_UPDATED", trip, actor, before, after);
        if (vehicleChanged) {
            // Phương án tính cho xe cũ: gỡ duyệt để kho không xếp theo nó; điều phối tối ưu lại rồi duyệt.
            loadPlanRepository.unapproveAllByTripId(trip.getId());
        }
        if (routeStale) {
            tripStatusService.invalidateRoute(trip, actor.getId());
        }
    }

    /**
     * Huỷ chuyến Nháp / Đã lập kế hoạch / Đang xếp hàng. Yêu cầu giao về Chờ xử lý và rời
     * chuyến, kiện về Chờ xử lý; phiên xếp đang mở bị dừng. Điểm giao ở lại làm lịch sử.
     */
    @Transactional
    public void cancelTrip(Long tripId, String reason) {
        User actor = currentUserService.getCurrentUser();
        Long companyId = companyOf(actor);
        Trip trip = requireTripForUpdate(tripId, companyId);
        if (!CANCELLABLE.contains(trip.getStatus())) {
            throw new AppException(ErrorCode.TRIP_NOT_CANCELLABLE);
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Vui lòng nhập lý do huỷ chuyến");
        }
        TripStatus previousStatus = trip.getStatus();

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
        audit("TRIP_CANCELLED", trip, actor, Map.of("status", previousStatus.name()), after);
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
        audit("TRIP_REQUIREMENT_REMOVED", trip, actor, Map.of(), after);

        tripStatusService.invalidateRoute(trip, actor.getId());
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

    /** Điều kiện cần trên tổng hàng (không xếp thử): tải trọng và từng kiện lọt lòng thùng ở một hướng nào đó. */
    private void requireCarriesCargo(Vehicle vehicle, Long tripId) {
        VehicleType type = vehicle.getVehicleType();
        if (type == null) {
            return;
        }
        List<CargoPackage> packages = tripPackageResolver.packagesOfTrip(tripId);
        BigDecimal totalKg = packages.stream()
                .map(CargoPackage::getActualWeightKg)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (type.getMaxPayloadKg() != null && totalKg.compareTo(type.getMaxPayloadKg()) > 0) {
            throw new AppException(ErrorCode.VEHICLE_CANNOT_CARRY_CARGO,
                    "Hàng của chuyến nặng " + totalKg.stripTrailingZeros().toPlainString()
                            + " kg, vượt tải trọng " + type.getMaxPayloadKg().stripTrailingZeros().toPlainString() + " kg của xe");
        }
        if (type.getInnerLength() == null || type.getInnerWidth() == null || type.getInnerHeight() == null) {
            return;
        }
        double[] box = sortedDesc(type.getInnerLength(), type.getInnerWidth(), type.getInnerHeight());
        for (CargoPackage pkg : packages) {
            if (pkg.getActualLength() == null || pkg.getActualWidth() == null || pkg.getActualHeight() == null) {
                continue;
            }
            double[] size = sortedDesc(pkg.getActualLength().doubleValue(), pkg.getActualWidth().doubleValue(),
                    pkg.getActualHeight().doubleValue());
            if (size[0] > box[0] || size[1] > box[1] || size[2] > box[2]) {
                throw new AppException(ErrorCode.VEHICLE_CANNOT_CARRY_CARGO,
                        "Kiện " + pkg.getTrackingBarcode() + " không lọt lòng thùng của xe");
            }
        }
    }

    private static double[] sortedDesc(double a, double b, double c) {
        double[] values = {a, b, c};
        Arrays.sort(values);
        return new double[]{values[2], values[1], values[0]};
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
