package fu.se184491.loadmaster_be.service.pickup.impl;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.pickup.ApprovePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.CreatePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.PickupPackageRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.PickupPointRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.RejectPickupRequest;
import fu.se184491.loadmaster_be.dto.response.pickup.PickupRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.cargo.TrackingBarcodeAllocator;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.pickup.FreedSpacePacker;
import fu.se184491.loadmaster_be.service.pickup.PickupContextBuilder;
import fu.se184491.loadmaster_be.service.pickup.PickupRequestService;
import fu.se184491.loadmaster_be.service.pickup.PickupRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Yêu cầu nhận hàng dọc đường ở máy chủ. Mười luật tính ở {@link PickupRules} trên ngữ cảnh của {@link PickupContextBuilder}; duyệt
 * tạo kiện kho kiện (mã QR thật), chèn điểm nhận và điểm giao ngay sau điểm hiện tại (điểm giao trùng điểm có sẵn thì dùng lại) và
 * lưu chỗ xếp kiện nhận. Phương án đã duyệt của chuyến không đổi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PickupRequestServiceImpl implements PickupRequestService {

    /** Dời tạm số thứ tự điểm khi chèn, để không va ràng buộc (chuyến, số thứ tự) duy nhất giữa chừng. */
    private static final int SEQUENCE_SHIFT = 10_000;

    private final PickupRequestRepository pickupRequestRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;
    private final TripLookupHelper tripLookupHelper;
    private final PickupContextBuilder contextBuilder;

    @Override
    @Transactional(readOnly = true)
    public List<PickupRequestResponse> list(String tripIdOrCode) {
        Trip trip = readableTrip(tripIdOrCode);
        return pickupRequestRepository.findByTripIdOrderByIdAsc(trip.getId()).stream()
                .map(PickupRequestResponse::fromEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PickupRequestResponse get(String tripIdOrCode, Long pickupId) {
        Trip trip = readableTrip(tripIdOrCode);
        return PickupRequestResponse.fromEntity(requireRequest(trip, pickupId));
    }

    @Override
    @Transactional
    public PickupRequestResponse create(String tripIdOrCode, CreatePickupRequest body) {
        User user = currentUserService.getCurrentUser();
        Trip trip = companyTrip(tripIdOrCode);
        boolean dispatcher = currentUserService.hasRole("DISPATCHER");
        boolean ownDriver = currentUserService.hasRole("DRIVER") && trip.getDriver() != null
                && Objects.equals(trip.getDriver().getId(), user.getId());
        if (!dispatcher && !ownDriver) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        requireInTransit(trip);
        if (body == null || body.getPickup() == null || body.getDelivery() == null) {
            throw new AppException(ErrorCode.PICKUP_INVALID);
        }
        if (body.getPackages() == null || body.getPackages().isEmpty()) {
            throw new AppException(ErrorCode.PICKUP_PACKAGES_REQUIRED);
        }

        PickupRequest request = PickupRequest.builder()
                .trip(trip)
                .company(trip.getCompany())
                .deadline(body.getDeadline())
                .status(PickupRequestStatus.PENDING)
                .createdBy(user)
                .createdAt(MonitoringTime.now())
                .build();
        applyPoint(body.getPickup(), true, request);
        applyPoint(body.getDelivery(), false, request);
        int index = 0;
        for (PickupPackageRequest item : body.getPackages()) {
            if (item == null || item.getPackageCode() == null || item.getPackageCode().isBlank() || !positive(item.getLengthCm())
                    || !positive(item.getWidthCm()) || !positive(item.getHeightCm()) || !positive(item.getWeightKg())) {
                throw new AppException(ErrorCode.PICKUP_INVALID);
            }
            request.getPackages().add(PickupRequestPackage.builder()
                    .pickupRequest(request)
                    .lineIndex(index++)
                    .packageCode(item.getPackageCode().trim())
                    .lengthCm(item.getLengthCm())
                    .widthCm(item.getWidthCm())
                    .heightCm(item.getHeightCm())
                    .weightKg(item.getWeightKg())
                    .handlingClass(item.getHandlingClass() != null ? item.getHandlingClass() : HandlingClass.STANDARD)
                    .build());
        }

        // Kiểm luật trước khi ghi: chuyến chưa kiểm được thì không lưu gì
        PickupContextBuilder.Built built = contextBuilder.build(trip, request, null);
        List<PickupRules.RuleResult> results = PickupRules.evaluate(built.context());
        request.setValidationResults(serialize(results));
        request.setValidatedAt(MonitoringTime.now());
        request.setStatus(PickupRules.allPassed(results) ? PickupRequestStatus.VALIDATED : PickupRequestStatus.PENDING);
        PickupRequest saved = pickupRequestRepository.save(request);
        audit(trip, user, "PICKUP_REQUESTED", saved, Map.of("status", saved.getStatus().name(),
                "packages", saved.getPackages().size()));
        log.info("Pickup request {} created on trip {} ({})", saved.getId(), trip.getTripCode(), saved.getStatus());
        return PickupRequestResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public PickupRequestResponse validate(String tripIdOrCode, Long pickupId) {
        Trip trip = companyTrip(tripIdOrCode);
        requireDispatcherOrOwnDriver(trip);
        requireInTransit(trip);
        PickupRequest request = requireRequest(trip, pickupId);
        if (!request.getStatus().open()) {
            throw new AppException(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION);
        }
        List<PickupRules.RuleResult> results = PickupRules.evaluate(contextBuilder.build(trip, request, null).context());
        request.setValidationResults(serialize(results));
        request.setValidatedAt(MonitoringTime.now());
        PickupRequestStatus next = PickupRules.allPassed(results) ? PickupRequestStatus.VALIDATED : PickupRequestStatus.PENDING;
        if (next != request.getStatus()) {
            moveTo(request, next);
        }
        return PickupRequestResponse.fromEntity(pickupRequestRepository.save(request));
    }

    @Override
    @Transactional
    public PickupRequestResponse approve(String tripIdOrCode, Long pickupId, ApprovePickupRequest body) {
        User user = currentUserService.getCurrentUser();
        Trip trip = companyTrip(tripIdOrCode);
        requireDispatcher();
        requireInTransit(trip);
        PickupRequest request = requireRequest(trip, pickupId);
        if (!request.getStatus().open()) {
            throw new AppException(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION);
        }
        String reason = body != null && body.getOverrideReason() != null ? body.getOverrideReason().trim() : "";

        PickupContextBuilder.Built built = contextBuilder.build(trip, request, reason);
        List<PickupRules.RuleResult> results = PickupRules.evaluate(built.context());
        boolean allPassed = PickupRules.allPassed(results);
        if (!allPassed && reason.isEmpty()) {
            throw new AppException(ErrorCode.PICKUP_REASON_REQUIRED);
        }
        PickupRules.Insertion insertion = built.insertion();
        if (insertion == null) {
            throw new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP);
        }

        createPackages(trip, request);
        insertStops(built.stops(), insertion, request);

        LocalDateTime now = MonitoringTime.now();
        FreedSpacePacker.Result packing = built.context().packing();
        request.setLayout(layoutOf(packing, now));
        request.setValidationResults(serialize(results));
        request.setValidatedAt(now);
        request.setOverrideReason(allPassed ? null : reason);
        request.setApprovedBy(user);
        request.setApprovedAt(now);
        moveTo(request, PickupRequestStatus.APPROVED);
        PickupRequest saved = pickupRequestRepository.save(request);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("placed", packing.placements().size());
        values.put("unplaced", packing.unplaced().size());
        values.put("pickupStopId", saved.getPickupStop().getId());
        values.put("deliveryStopId", saved.getDeliveryStop().getId());
        values.put("overridden", !allPassed);
        if (trip.getDriver() != null) {
            values.put("driverId", trip.getDriver().getId());
        }
        audit(trip, user, "PICKUP_APPROVED", saved, values);
        log.info("Pickup request {} approved on trip {}: {} placed, {} unplaced", saved.getId(), trip.getTripCode(),
                packing.placements().size(), packing.unplaced().size());
        return PickupRequestResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public PickupRequestResponse reject(String tripIdOrCode, Long pickupId, RejectPickupRequest body) {
        User user = currentUserService.getCurrentUser();
        Trip trip = companyTrip(tripIdOrCode);
        requireDispatcher();
        PickupRequest request = requireRequest(trip, pickupId);
        String reason = body != null && body.getReason() != null ? body.getReason().trim() : "";
        if (reason.isEmpty()) {
            throw new AppException(ErrorCode.PICKUP_REASON_REQUIRED);
        }
        if (!request.getStatus().open()) {
            throw new AppException(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION);
        }
        moveTo(request, PickupRequestStatus.REJECTED);
        request.setRejectReason(reason);
        request.setRejectedBy(user);
        request.setRejectedAt(MonitoringTime.now());
        PickupRequest saved = pickupRequestRepository.save(request);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reason", reason);
        if (trip.getDriver() != null) {
            values.put("driverId", trip.getDriver().getId());
        }
        audit(trip, user, "PICKUP_REJECTED", saved, values);
        return PickupRequestResponse.fromEntity(saved);
    }

    // ---------------------------------------------------------------------------------------------

    /** Kiện kho kiện cho từng kiện nhận: mã kho cấp từ mã của bên gửi (thêm hậu tố khi trùng), mã QR thật. */
    private void createPackages(Trip trip, PickupRequest request) {
        TrackingBarcodeAllocator codes = new TrackingBarcodeAllocator(cargoPackageRepository::existsByPackageCode);
        TrackingBarcodeAllocator barcodes = new TrackingBarcodeAllocator(cargoPackageRepository::existsByTrackingBarcode);
        for (PickupRequestPackage line : request.getPackages()) {
            String code = codes.allocate(line.getPackageCode());
            CargoPackage cargo = cargoPackageRepository.save(CargoPackage.builder()
                    .company(trip.getCompany())
                    .packageCode(code)
                    .packageName(line.getPackageCode())
                    .trackingBarcode(barcodes.allocate(code))
                    .handlingClass(line.getHandlingClass() != null ? line.getHandlingClass() : HandlingClass.STANDARD)
                    .actualLength(line.getLengthCm())
                    .actualWidth(line.getWidthCm())
                    .actualHeight(line.getHeightCm())
                    .actualWeightKg(line.getWeightKg())
                    .destination(request.getDeliveryAddress())
                    .status(PackageStatus.PLANNED)
                    .build());
            line.setCargoPackage(cargo);
        }
    }

    /**
     * Chèn điểm nhận (và điểm giao mới, trừ khi dùng lại điểm có sẵn) ngay sau điểm hiện tại; các điểm sau lùi số thứ tự. Hạn của điểm
     * giao dùng lại là hạn sớm hơn.
     */
    private void insertStops(List<DeliveryStop> stops, PickupRules.Insertion insertion, PickupRequest request) {
        DeliveryStop current = stops.get(insertion.currentIndex());
        Trip trip = current.getTrip();
        boolean reuse = insertion.reusedIndex() >= 0;
        int offset = reuse ? 1 : 2;
        List<DeliveryStop> later = stops.subList(insertion.currentIndex() + 1, stops.size());
        if (!later.isEmpty()) {
            later.forEach(stop -> stop.setStopSequence(stop.getStopSequence() + SEQUENCE_SHIFT));
            deliveryStopRepository.saveAllAndFlush(later);
            later.forEach(stop -> stop.setStopSequence(stop.getStopSequence() - SEQUENCE_SHIFT + offset));
            deliveryStopRepository.saveAllAndFlush(later);
        }
        int base = current.getStopSequence();
        DeliveryStop pickupStop = deliveryStopRepository.save(DeliveryStop.builder()
                .trip(trip)
                .stopSequence(base + 1)
                .stopName(request.getPickupName())
                .address(request.getPickupAddress())
                .latitude(request.getPickupLatitude())
                .longitude(request.getPickupLongitude())
                .status(DeliveryStopStatus.PENDING)
                .stopType(DeliveryStopType.PICKUP)
                .build());
        DeliveryStop deliveryStop;
        if (reuse) {
            deliveryStop = stops.get(insertion.reusedIndex());
            if (request.getDeadline() != null && (deliveryStop.getPlannedArrival() == null
                    || request.getDeadline().isBefore(deliveryStop.getPlannedArrival()))) {
                deliveryStop.setPlannedArrival(request.getDeadline());
                deliveryStopRepository.save(deliveryStop);
            }
        } else {
            deliveryStop = deliveryStopRepository.save(DeliveryStop.builder()
                    .trip(trip)
                    .stopSequence(base + 2)
                    .stopName(request.getDeliveryName())
                    .address(request.getDeliveryAddress())
                    .latitude(request.getDeliveryLatitude())
                    .longitude(request.getDeliveryLongitude())
                    .status(DeliveryStopStatus.PENDING)
                    .stopType(DeliveryStopType.DELIVERY)
                    .plannedArrival(request.getDeadline())
                    .build());
        }
        request.setPickupStop(pickupStop);
        request.setDeliveryStop(deliveryStop);
    }

    static Map<String, Object> layoutOf(FreedSpacePacker.Result packing, LocalDateTime plannedAt) {
        List<Map<String, Object>> placements = new ArrayList<>();
        for (FreedSpacePacker.Placement placement : packing.placements()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("packageIndex", placement.index());
            item.put("rotation", placement.rotation());
            item.put("xCm", placement.x());
            item.put("yCm", placement.y());
            item.put("zCm", placement.z());
            item.put("placedLengthCm", placement.length());
            item.put("placedWidthCm", placement.width());
            item.put("placedHeightCm", placement.height());
            placements.add(item);
        }
        List<Map<String, Object>> unplaced = new ArrayList<>();
        for (FreedSpacePacker.Unplaced item : packing.unplaced()) {
            unplaced.add(Map.of("packageIndex", item.index(), "reasonCode", item.reasonCode()));
        }
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("placements", placements);
        layout.put("unplaced", unplaced);
        layout.put("plannedAt", plannedAt.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return layout;
    }

    static List<Map<String, Object>> serialize(List<PickupRules.RuleResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PickupRules.RuleResult result : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule", result.rule());
            row.put("passed", result.passed());
            row.put("code", result.code());
            row.put("params", result.params());
            rows.add(row);
        }
        return rows;
    }

    private static void moveTo(PickupRequest request, PickupRequestStatus next) {
        if (!request.getStatus().canMoveTo(next)) {
            throw new AppException(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION);
        }
        request.setStatus(next);
    }

    private static void applyPoint(PickupPointRequest point, boolean pickup, PickupRequest request) {
        if (point.getName() == null || point.getName().isBlank() || point.getAddress() == null || point.getAddress().isBlank()
                || !coordinate(point.getLatitude(), 90) || !coordinate(point.getLongitude(), 180)) {
            throw new AppException(ErrorCode.PICKUP_INVALID);
        }
        if (pickup) {
            request.setPickupName(point.getName().trim());
            request.setPickupAddress(point.getAddress().trim());
            request.setPickupLatitude(point.getLatitude());
            request.setPickupLongitude(point.getLongitude());
        } else {
            request.setDeliveryName(point.getName().trim());
            request.setDeliveryAddress(point.getAddress().trim());
            request.setDeliveryLatitude(point.getLatitude());
            request.setDeliveryLongitude(point.getLongitude());
        }
    }

    private static boolean coordinate(BigDecimal value, int limit) {
        return value != null && value.abs().compareTo(BigDecimal.valueOf(limit)) <= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private Trip companyTrip(String tripIdOrCode) {
        Long companyId = currentUserService.getCurrentCompanyId();
        if (companyId == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        return tripLookupHelper.requireTrip(tripIdOrCode, companyId);
    }

    /** Điều phối viên, quản lý công ty đọc mọi chuyến của công ty; tài xế chỉ chuyến của mình. */
    private Trip readableTrip(String tripIdOrCode) {
        Trip trip = companyTrip(tripIdOrCode);
        if (currentUserService.hasRole("DISPATCHER") || currentUserService.hasRole("MANAGER")) {
            return trip;
        }
        requireOwnDriver(trip);
        return trip;
    }

    private void requireDispatcherOrOwnDriver(Trip trip) {
        if (!currentUserService.hasRole("DISPATCHER")) {
            requireOwnDriver(trip);
        }
    }

    private void requireOwnDriver(Trip trip) {
        User user = currentUserService.getCurrentUser();
        if (!currentUserService.hasRole("DRIVER") || trip.getDriver() == null
                || !Objects.equals(trip.getDriver().getId(), user.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void requireDispatcher() {
        if (!currentUserService.hasRole("DISPATCHER")) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static void requireInTransit(Trip trip) {
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.PICKUP_TRIP_NOT_IN_TRANSIT);
        }
    }

    private PickupRequest requireRequest(Trip trip, Long pickupId) {
        return pickupRequestRepository.findByIdAndTripId(pickupId, trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.PICKUP_REQUEST_NOT_FOUND));
    }

    private void audit(Trip trip, User user, String action, PickupRequest request, Map<String, Object> values) {
        Map<String, Object> newValues = new LinkedHashMap<>(values);
        newValues.put("pickupId", request.getId());
        newValues.put("tripId", trip.getId());
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(action)
                .entityName("PickupRequest")
                .entityId(String.valueOf(request.getId()))
                .user(user)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());
    }
}
