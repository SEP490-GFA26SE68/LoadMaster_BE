package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.trip.ReorderStopsResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Đổi thứ tự các điểm chưa giao của chuyến Đang vận chuyển (POST /api/trips/{id}/stops/reorder), chỉ điều phối viên. Điểm đã hoàn tất
 * và điểm xe đã tới đứng nguyên; điểm nhận dọc đường đứng trước điểm giao của nó; thứ tự mới làm một kiện còn trên xe bị che kín lối dỡ
 * thì từ chối, che một phần thì trả cảnh báo. Phương án đã duyệt không đổi; giờ đến dự kiến tính lại lúc đọc theo thứ tự mới.
 */
@Service
@RequiredArgsConstructor
public class StopReorderService {

    /** Dời tạm số thứ tự để không vướng ràng buộc (chuyến, số thứ tự) duy nhất giữa chừng. */
    private static final int SEQUENCE_SHIFT = 10_000;
    private static final Set<PickupRequestStatus> ON_BOARD = EnumSet.of(PickupRequestStatus.APPROVED, PickupRequestStatus.LOADED);

    private final CurrentUserService currentUserService;
    private final TripLookupHelper tripLookupHelper;
    private final DeliveryStopRepository deliveryStopRepository;
    private final PickupRequestRepository pickupRequestRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final UnloadConfirmationRepository unloadConfirmationRepository;
    private final TripPackageResolver tripPackageResolver;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public ReorderStopsResponse reorder(String tripIdOrCode, List<Long> proposed) {
        User actor = currentUserService.getCurrentUser();
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, currentUserService.getCurrentCompanyId());
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.TRIP_NOT_IN_TRANSIT);
        }

        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        List<Long> current = stops.stream().map(DeliveryStop::getId).toList();
        int fixedCount = 0;
        for (int index = 0; index < stops.size(); index++) {
            DeliveryStop stop = stops.get(index);
            if (stop.getStatus() == DeliveryStopStatus.COMPLETED || stop.getActualArrival() != null) {
                fixedCount = index + 1;
            }
        }
        List<StopReorderRules.PickupPair> pickups = pickupRequestRepository
                .findByTripIdAndStatusIn(trip.getId(), EnumSet.of(PickupRequestStatus.APPROVED)).stream()
                .filter(request -> request.getPickupStop() != null && request.getDeliveryStop() != null)
                .map(request -> new StopReorderRules.PickupPair(request.getPickupStop().getId(), request.getDeliveryStop().getId()))
                .toList();

        List<Long> order = proposed == null ? List.of() : proposed;
        StopReorderRules.Violation violation = StopReorderRules.checkProposedOrder(current, order, fixedCount, pickups);
        if (violation != null) {
            ErrorCode code = ErrorCode.valueOf(violation.code());
            throw violation.stopIds().isEmpty()
                    ? new AppException(code)
                    : new AppException(code, Map.of("stopIds", violation.stopIds().stream().map(String::valueOf).toList()));
        }

        // Khả năng dỡ: kiện nào bị che kín theo thứ tự mới mà trước đó chưa bị thì từ chối
        List<StopReorderRules.Cargo> cargo = onboardCargo(trip, stops);
        StopReorderRules.Unloading before = StopReorderRules.checkUnloading(cargo, current);
        StopReorderRules.Unloading after = StopReorderRules.checkUnloading(cargo, order);
        List<StopReorderRules.Cargo> newlyBlocked = cargo.stream()
                .filter(item -> after.blocked().contains(item.key()) && !before.blocked().contains(item.key()))
                .toList();
        if (!newlyBlocked.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("packages", newlyBlocked.stream().map(StopReorderRules.Cargo::label).toList());
            details.put("stopIds", newlyBlocked.stream().map(item -> String.valueOf(item.stopId())).toList());
            throw new AppException(ErrorCode.STOP_ORDER_BLOCKS_CARGO, details);
        }
        List<ReorderStopsResponse.Partial> partial = cargo.stream()
                .filter(item -> after.partial().contains(item.key())
                        && !before.partial().contains(item.key()) && !before.blocked().contains(item.key()))
                .map(item -> new ReorderStopsResponse.Partial(item.label(), item.stopId()))
                .toList();

        Map<Long, DeliveryStop> byId = stops.stream().collect(Collectors.toMap(DeliveryStop::getId, stop -> stop));
        stops.forEach(stop -> stop.setStopSequence(stop.getStopSequence() + SEQUENCE_SHIFT));
        deliveryStopRepository.saveAllAndFlush(stops);
        List<DeliveryStop> reordered = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) {
            DeliveryStop stop = byId.get(order.get(index));
            stop.setStopSequence(index + 1);
            reordered.add(stop);
        }
        deliveryStopRepository.saveAllAndFlush(reordered);

        int moved = 0;
        for (int index = 0; index < order.size(); index++) {
            if (!order.get(index).equals(current.get(index))) {
                moved++;
            }
        }
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("count", moved);
        newValues.put("tripId", trip.getId());
        if (trip.getDriver() != null) {
            newValues.put("driverId", trip.getDriver().getId());
        }
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("TRIP_STOPS_REORDERED")
                .entityName("Trip")
                .entityId(String.valueOf(trip.getId()))
                .user(actor)
                .oldValues(Map.of("stopIds", current.stream().map(String::valueOf).toList()))
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());

        return new ReorderStopsResponse(trip.getId(), trip.getTripCode(),
                reordered.stream().map(stop -> new ReorderStopsResponse.Stop(stop.getId(), stop.getStopSequence())).toList(),
                partial);
    }

    /**
     * Kiện còn phải dỡ: kiện của phương án kho đã xếp (đã xác nhận chỗ, sai lệch thì lấy vị trí thật) chưa dỡ, chưa giao, thuộc điểm chưa
     * hoàn tất; cộng kiện nhận dọc đường đã có chỗ của yêu cầu đã duyệt, chưa giao.
     */
    private List<StopReorderRules.Cargo> onboardCargo(Trip trip, List<DeliveryStop> stops) {
        Map<Long, Long> stopOfPackage = new HashMap<>();
        Set<Long> unloaded = new HashSet<>();
        Set<Long> open = new HashSet<>();
        for (DeliveryStop stop : stops) {
            if (stop.getStatus() != DeliveryStopStatus.COMPLETED) {
                open.add(stop.getId());
            }
            tripPackageResolver.packagesOfStop(stop.getId()).forEach(item -> stopOfPackage.putIfAbsent(item.getId(), stop.getId()));
            unloadConfirmationRepository.findByDeliveryStopId(stop.getId()).forEach(confirmation -> {
                if (confirmation.getCargoPackage() != null) {
                    unloaded.add(confirmation.getCargoPackage().getId());
                }
            });
        }

        List<StopReorderRules.Cargo> cargo = new ArrayList<>();
        LoadingExecution execution = loadingExecutionRepository.findAllByLoadPlanJobTripId(trip.getId()).stream()
                .filter(item -> item.getStatus() == LoadingExecutionStatus.COMPLETED)
                .max((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElse(null);
        if (execution != null) {
            Long planId = execution.getLoadPlan().getId();
            Set<Long> confirmed = placementConfirmationRepository.findByPlacementLoadPlanId(planId).stream()
                    .map(confirmation -> confirmation.getPlacement() != null ? confirmation.getPlacement().getId() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, Deviation> deviations = new HashMap<>();
            for (Deviation deviation : deviationRepository.findByPlacementLoadPlanId(planId)) {
                if (deviation.getPlacement() != null) {
                    deviations.putIfAbsent(deviation.getPlacement().getId(), deviation);
                }
            }
            for (PackagePlacement placement : packagePlacementRepository.findByLoadPlanId(planId)) {
                CargoPackage item = placement.getCargoPackage();
                Deviation deviation = deviations.get(placement.getId());
                if (item == null || (!confirmed.contains(placement.getId()) && deviation == null)
                        || unloaded.contains(item.getId()) || item.getStatus() == PackageStatus.DELIVERED
                        || placement.getPackedLength() == null || placement.getPackedWidth() == null
                        || placement.getPackedHeight() == null) {
                    continue;
                }
                Long stopId = stopOfPackage.getOrDefault(item.getId(),
                        placement.getStopZone() != null ? placement.getStopZone().getId() : null);
                if (stopId == null || !open.contains(stopId)) {
                    continue;
                }
                boolean moved = deviation != null && deviation.getActualPosX() != null && deviation.getActualPosY() != null
                        && deviation.getActualPosZ() != null;
                cargo.add(new StopReorderRules.Cargo("P" + placement.getId(), labelOf(item), stopId,
                        moved ? deviation.getActualPosX() : number(placement.getPosX()),
                        moved ? deviation.getActualPosY() : number(placement.getPosY()),
                        moved ? deviation.getActualPosZ() : number(placement.getPosZ()),
                        placement.getPackedLength(), placement.getPackedWidth(), placement.getPackedHeight()));
            }
        }

        for (PickupRequest request : pickupRequestRepository.findByTripIdAndStatusIn(trip.getId(), ON_BOARD)) {
            DeliveryStop delivery = request.getDeliveryStop();
            if (delivery == null || !open.contains(delivery.getId())) {
                continue;
            }
            Map<Integer, PickupRequestPackage> lines = new HashMap<>();
            request.getPackages().forEach(line -> lines.put(line.getLineIndex(), line));
            for (Map<String, Object> spot : placementsOf(request)) {
                PickupRequestPackage line = lines.get(toInt(spot.get("packageIndex")));
                CargoPackage item = line == null ? null : line.getCargoPackage();
                if (item != null && (item.getStatus() == PackageStatus.DELIVERED || item.getStatus() == PackageStatus.RETURNED
                        || unloaded.contains(item.getId()))) {
                    continue;
                }
                String label = item != null ? labelOf(item) : line != null ? line.getPackageCode() : "PICKUP-" + request.getId();
                cargo.add(new StopReorderRules.Cargo("R" + request.getId() + "-" + toInt(spot.get("packageIndex")), label,
                        delivery.getId(),
                        toDouble(spot.get("xCm")), toDouble(spot.get("yCm")), toDouble(spot.get("zCm")),
                        toDouble(spot.get("placedLengthCm")), toDouble(spot.get("placedWidthCm")),
                        toDouble(spot.get("placedHeightCm"))));
            }
        }
        return cargo;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> placementsOf(PickupRequest request) {
        Map<String, Object> layout = request.getLayout();
        if (layout == null || !(layout.get("placements") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> placements = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                placements.add((Map<String, Object>) raw);
            }
        }
        return placements;
    }

    /** Mã người đọc thấy trên nhãn: mã của bên gửi, không có thì mã số kiện. */
    private static String labelOf(CargoPackage item) {
        return item.getPackageCode() != null && !item.getPackageCode().isBlank() ? item.getPackageCode() : String.valueOf(item.getId());
    }

    private static double number(Integer value) {
        return value == null ? 0 : value;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }
}
