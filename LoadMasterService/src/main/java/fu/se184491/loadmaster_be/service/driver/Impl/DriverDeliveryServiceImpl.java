package fu.se184491.loadmaster_be.service.driver.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.DeliveryIssueKind;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.monitoring.DeadlineStatus;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.driver.RejectManualConfirmRequest;
import fu.se184491.loadmaster_be.dto.request.driver.ReportDeliveryIssueRequest;
import fu.se184491.loadmaster_be.dto.response.driver.DeliveryIssueResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverLoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.driver.DriverTripStopsResponse;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripLiveStopResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.driver.DeliveryIssue;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.DuplicateUnloadException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.WrongStopException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.DeliveryIssueRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.driver.DriverDeliveryService;
import fu.se184491.loadmaster_be.service.driver.DriverService;
import fu.se184491.loadmaster_be.service.monitoring.DeadlineStatusRule;
import fu.se184491.loadmaster_be.service.monitoring.EtaCalculationService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.service.warehouse.StagingRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverDeliveryServiceImpl implements DriverDeliveryService {

    /** Chuyến Đang vận chuyển: màn tài xế đọc lại giờ đến sau ngần này (như màn Giám sát). */
    static final int IN_TRANSIT_REFRESH_MS = 30_000;

    private final DriverService driverService;
    private final CurrentUserService currentUserService;
    private final TripLookupHelper tripLookupHelper;
    private final TripPackageResolver tripPackageResolver;
    private final DeliveryStopRepository deliveryStopRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final UnloadConfirmationRepository unloadConfirmationRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final DeliveryIssueRepository deliveryIssueRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final StagingConfirmationRepository stagingConfirmationRepository;
    private final StagingRecorder stagingRecorder;
    private final GpsTrackingRepository gpsTrackingRepository;
    private final EtaCalculationService etaCalculationService;
    private final AuditLogRepository auditLogRepository;

    // ---------------------------------------------------------------------------------------------
    // Phương án kho đã xếp (khung 3D "Xem vị trí hàng")
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public DriverLoadPlanResponse getLoadPlan(String tripIdOrCode) {
        Trip trip = driverService.requireOwnTrip(tripIdOrCode);
        if (trip.getStatus() != TripStatus.LOADING && trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.NO_LOADED_PLAN);
        }
        LoadPlan plan = loadingExecutionRepository.findAllByLoadPlanJobTripId(trip.getId()).stream()
                .filter(execution -> execution.getStatus() == LoadingExecutionStatus.IN_PROGRESS
                        || execution.getStatus() == LoadingExecutionStatus.COMPLETED)
                .max(Comparator.comparing(LoadingExecution::getId))
                .map(LoadingExecution::getLoadPlan)
                .orElseThrow(() -> new AppException(ErrorCode.NO_LOADED_PLAN));

        Set<Long> confirmed = placementConfirmationRepository.findByPlacementLoadPlanId(plan.getId()).stream()
                .map(confirmation -> confirmation.getPlacement() != null ? confirmation.getPlacement().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Deviation> deviations = new HashMap<>();
        for (Deviation deviation : deviationRepository.findByPlacementLoadPlanId(plan.getId())) {
            if (deviation.getPlacement() != null) {
                deviations.putIfAbsent(deviation.getPlacement().getId(), deviation);
            }
        }

        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        Map<Long, DeliveryStop> stopByPackage = new HashMap<>();
        Set<Long> unloadedPackages = new HashSet<>();
        for (DeliveryStop stop : stops) {
            for (CargoPackage cargoPackage : tripPackageResolver.packagesOfStop(stop.getId())) {
                stopByPackage.putIfAbsent(cargoPackage.getId(), stop);
            }
            for (UnloadConfirmation confirmation : unloadConfirmationRepository.findByDeliveryStopId(stop.getId())) {
                if (confirmation.getCargoPackage() != null) {
                    unloadedPackages.add(confirmation.getCargoPackage().getId());
                }
            }
        }

        List<DriverLoadPlanResponse.Placement> placements = new ArrayList<>();
        for (PackagePlacement placement : packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(plan.getId())) {
            Deviation deviation = deviations.get(placement.getId());
            // Kiện kho chưa xác nhận xếp cũng chưa ghi sai lệch: không nằm trên xe
            if (!confirmed.contains(placement.getId()) && deviation == null) {
                continue;
            }
            placements.add(toPlacement(placement, deviation, stopByPackage, unloadedPackages));
        }
        // Thứ tự dỡ của cả chuyến: điểm giao trước dỡ trước; trong một điểm, kiện xếp sau dỡ trước (LIFO, như current-stop)
        List<DriverLoadPlanResponse.Placement> unloadOrder = new ArrayList<>(placements);
        unloadOrder.sort(Comparator
                .comparing(DriverLoadPlanResponse.Placement::getStopSequence, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DriverLoadPlanResponse.Placement::getStepSequence, Comparator.nullsLast(Comparator.reverseOrder())));
        for (int index = 0; index < unloadOrder.size(); index++) {
            unloadOrder.get(index).setUnloadOrder(index + 1);
        }

        Vehicle vehicle = trip.getVehicle();
        VehicleType type = vehicle != null ? vehicle.getVehicleType() : null;
        return DriverLoadPlanResponse.builder()
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .loadPlanId(plan.getId())
                .licensePlate(vehicle != null ? vehicle.getLicensePlate() : null)
                .innerLength(type != null ? type.getInnerLength() : null)
                .innerWidth(type != null ? type.getInnerWidth() : null)
                .innerHeight(type != null ? type.getInnerHeight() : null)
                .doorWidth(type != null ? type.getDoorWidth() : null)
                .doorHeight(type != null ? type.getDoorHeight() : null)
                .maxPayloadKg(type != null ? type.getMaxPayloadKg() : null)
                .stops(stops.stream().map(stop -> DriverLoadPlanResponse.Stop.builder()
                        .stopId(stop.getId())
                        .stopSequence(stop.getStopSequence())
                        .stopName(stop.getStopName())
                        .status(stop.getStatus() != null ? stop.getStatus().name() : null)
                        .build()).toList())
                .placements(placements)
                .build();
    }

    private static DriverLoadPlanResponse.Placement toPlacement(PackagePlacement placement, Deviation deviation,
                                                                Map<Long, DeliveryStop> stopByPackage, Set<Long> unloadedPackages) {
        CargoPackage pkg = placement.getCargoPackage();
        DeliveryStop stop = pkg != null ? stopByPackage.get(pkg.getId()) : null;
        if (stop == null) {
            stop = placement.getStopZone();
        }
        boolean moved = deviation != null && deviation.getActualPosX() != null
                && deviation.getActualPosY() != null && deviation.getActualPosZ() != null;
        boolean unloaded = pkg != null && (unloadedPackages.contains(pkg.getId()) || pkg.getStatus() == PackageStatus.DELIVERED);
        return DriverLoadPlanResponse.Placement.builder()
                .placementId(placement.getId())
                .packageId(pkg != null ? pkg.getId() : null)
                .packageCode(pkg != null ? pkg.getPackageCode() : null)
                .trackingBarcode(pkg != null ? pkg.getTrackingBarcode() : null)
                .packageName(packageName(pkg))
                .posX(moved ? deviation.getActualPosX() : placement.getPosX())
                .posY(moved ? deviation.getActualPosY() : placement.getPosY())
                .posZ(moved ? deviation.getActualPosZ() : placement.getPosZ())
                .packedLength(placement.getPackedLength())
                .packedWidth(placement.getPackedWidth())
                .packedHeight(placement.getPackedHeight())
                .rotation(placement.getRotationType())
                .weightKg(weightOf(pkg))
                .stepSequence(placement.getStepSequence())
                .stopId(stop != null ? stop.getId() : null)
                .stopSequence(stop != null ? stop.getStopSequence() : null)
                .deviated(deviation != null)
                .unloaded(unloaded)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Giờ đến và hạn giao
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public DriverTripStopsResponse getStops(String tripIdOrCode) {
        Trip trip = driverService.requireOwnTrip(tripIdOrCode);
        List<DeliveryStop> stops = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
        boolean inTransit = trip.getStatus() == TripStatus.IN_TRANSIT;
        GpsTracking latest = inTransit ? gpsTrackingRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId()).orElse(null) : null;

        // Giờ đến dự kiến từ vị trí GPS: cùng cách tính (và cùng cảnh báo trễ hạn) với màn Giám sát
        Map<String, LocalDateTime> etaByStop = new HashMap<>();
        if (latest != null) {
            for (TripLiveStopResponse live : etaCalculationService.recalculateAndAlert(trip, latest)) {
                LocalDateTime eta = localOf(live.getEta());
                if (live.getStopId() != null && eta != null) {
                    etaByStop.put(live.getStopId(), eta);
                }
            }
        }

        List<DriverTripStopsResponse.Stop> rows = new ArrayList<>();
        for (DeliveryStop stop : stops) {
            LocalDateTime deadline = stop.getDeliveryRequirement() != null ? stop.getDeliveryRequirement().getDeadline() : null;
            LocalDateTime eta = stop.getStatus() == DeliveryStopStatus.COMPLETED ? null : etaByStop.get(String.valueOf(stop.getId()));
            rows.add(DriverTripStopsResponse.Stop.builder()
                    .stopId(stop.getId())
                    .stopSequence(stop.getStopSequence())
                    .stopName(stop.getStopName())
                    .address(stop.getAddress())
                    .status(stop.getStatus() != null ? stop.getStatus().name() : null)
                    .packageCount(tripPackageResolver.packagesOfStop(stop.getId()).size())
                    .plannedArrival(stop.getPlannedArrival())
                    .actualArrival(stop.getActualArrival())
                    .deadline(deadline)
                    .plannedStatus(nameOf(DeadlineStatusRule.resolve(stop.getPlannedArrival(), deadline)))
                    .eta(eta)
                    .etaStatus(nameOf(DeadlineStatusRule.resolve(eta, deadline)))
                    .build());
        }

        return DriverTripStopsResponse.builder()
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .status(trip.getStatus() != null ? trip.getStatus().name() : null)
                .location(latest == null ? null : DriverTripStopsResponse.Location.builder()
                        .lat(latest.getLatitude() != null ? latest.getLatitude().doubleValue() : null)
                        .lng(latest.getLongitude() != null ? latest.getLongitude().doubleValue() : null)
                        .source(latest.getSource() != null ? latest.getSource().name() : null)
                        .recordedAt(latest.getRecordedAt())
                        .build())
                .refreshMs(inTransit ? IN_TRANSIT_REFRESH_MS : null)
                .stops(rows)
                .build();
    }

    /** Giờ của {@code EtaCalculationService} ({@code MonitoringTime.toIso}: giờ Việt Nam viết kèm offset 0) về lại giờ Việt Nam. */
    static LocalDateTime localOf(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String nameOf(DeadlineStatus status) {
        return status != null ? status.name() : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Sự cố theo kiện
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public DeliveryIssueResponse reportIssue(Long packageId, ReportDeliveryIssueRequest request) {
        AtStop at = packageAtArrivedStop(packageId);
        String note = trimmed(request.getNote());
        if (request.getKind() == DeliveryIssueKind.OTHER && note == null) {
            throw new AppException(ErrorCode.DELIVERY_ISSUE_NOTE_REQUIRED);
        }
        CargoPackage pkg = at.pkg();
        boolean unloaded = unloadConfirmationRepository.existsByCargoPackageId(pkg.getId());
        if (request.getKind() == DeliveryIssueKind.REFUSED) {
            // Khách từ chối: kiện ở lại xe — bỏ lần dỡ và xác nhận tay còn chờ của nó; hoàn tất điểm thì kiện là hoàn trả
            unloadConfirmationRepository.findByCargoPackageId(pkg.getId()).ifPresent(unloadConfirmationRepository::delete);
            for (ManualUnloadConfirmation pending : pendingOf(at.stop(), pkg)) {
                manualConfirmationRepository.delete(pending);
            }
            if (pkg.getStatus() == PackageStatus.DELIVERED) {
                pkg.setStatus(PackageStatus.LOADED);
                cargoPackageRepository.save(pkg);
            }
            unloaded = false;
        }

        DeliveryIssue issue = deliveryIssueRepository.save(DeliveryIssue.builder()
                .trip(at.trip())
                .deliveryStop(at.stop())
                .cargoPackage(pkg)
                .kind(request.getKind())
                .note(note)
                .reportedBy(at.driver())
                .reportedAt(LocalDateTime.now())
                .build());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("stopId", at.stop().getId());
        values.put("tripId", at.trip().getId());
        values.put("kind", request.getKind().name());
        audit(at.trip(), at.driver(), "DELIVERY_ISSUE_REPORTED", "DeliveryIssue", issue.getId(), values);
        log.info("Driver {} reported {} for package {} at stop {}", at.driver().getId(), request.getKind(), pkg.getId(), at.stop().getId());

        return DeliveryIssueResponse.builder()
                .issueId(issue.getId())
                .packageId(pkg.getId())
                .stopId(at.stop().getId())
                .kind(issue.getKind().name())
                .note(issue.getNote())
                .reportedAt(issue.getReportedAt())
                .unloaded(unloaded)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Xác nhận tay khi dỡ
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public ManualConfirmationResponse requestManualUnload(Long packageId, ManualUnloadRequest request) {
        AtStop at = packageAtArrivedStop(packageId);
        String note = trimmed(request.getNote());
        if (request.getReason() == ManualConfirmReason.OTHER && note == null) {
            throw new AppException(ErrorCode.MANUAL_CONFIRM_NOTE_REQUIRED);
        }
        CargoPackage pkg = at.pkg();
        if (unloadConfirmationRepository.existsByCargoPackageId(pkg.getId()) || pkg.getStatus() == PackageStatus.DELIVERED) {
            throw new DuplicateUnloadException("Kiện hàng này đã được dỡ trước đó: " + pkg.getTrackingBarcode());
        }
        LocalDateTime now = LocalDateTime.now();
        // Kiện ghi đã dỡ ngay để tài xế làm tiếp; điều phối viên từ chối thì lần dỡ này bị gỡ
        unloadConfirmationRepository.save(UnloadConfirmation.builder()
                .cargoPackage(pkg)
                .deliveryStop(at.stop())
                .driver(at.driver())
                .unloadedAt(now)
                .build());
        pkg.setStatus(PackageStatus.DELIVERED);
        cargoPackageRepository.save(pkg);

        ManualUnloadConfirmation confirmation = manualConfirmationRepository.save(ManualUnloadConfirmation.builder()
                .trip(at.trip())
                .deliveryStop(at.stop())
                .cargoPackage(pkg)
                .reason(request.getReason())
                .note(note)
                .status(ManualConfirmStatus.PENDING)
                .requestedBy(at.driver())
                .requestedAt(now)
                .build());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("stopId", at.stop().getId());
        values.put("tripId", at.trip().getId());
        values.put("reason", request.getReason().name());
        audit(at.trip(), at.driver(), "MANUAL_CONFIRM_REQUESTED", "ManualUnloadConfirmation", confirmation.getId(), values);
        log.info("Driver {} requested manual unload for package {} at stop {}", at.driver().getId(), pkg.getId(), at.stop().getId());
        return toResponse(confirmation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManualConfirmationResponse> listPendingManualConfirmations(String tripIdOrCode) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, currentUserService.getCurrentCompanyId());
        // Đang xếp hàng: xác nhận tay của kho (soạn, xếp); Đang vận chuyển: của tài xế (dỡ)
        if (trip.getStatus() != TripStatus.IN_TRANSIT && trip.getStatus() != TripStatus.LOADING) {
            return List.of();
        }
        return manualConfirmationRepository.findByTripIdAndStatusOrderByRequestedAtAsc(trip.getId(), ManualConfirmStatus.PENDING)
                .stream()
                .filter(entry -> stepMatches(entry, trip))
                .map(DriverDeliveryServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public ManualConfirmationResponse approveManualConfirmation(String tripIdOrCode, Long confirmationId) {
        ManualUnloadConfirmation confirmation = pendingConfirmation(tripIdOrCode, confirmationId);
        User dispatcher = currentUserService.getCurrentUser();
        confirmation.setStatus(ManualConfirmStatus.APPROVED);
        confirmation.setDecidedBy(dispatcher);
        confirmation.setDecidedAt(LocalDateTime.now());
        manualConfirmationRepository.save(confirmation);
        // Xác nhận tay lúc soạn chỉ tính là đã soạn khi được duyệt: kiện vào khu chờ dưới tên người kho đã gửi.
        if (confirmation.effectiveStep() == ManualConfirmStep.STAGING) {
            stagingRecorder.record(confirmation.getTrip(), confirmation.getCargoPackage(), StagingMethod.MANUAL,
                    confirmation.getRequestedBy(), dispatcher);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", confirmation.getCargoPackage().getId());
        values.put("tripId", confirmation.getTrip().getId());
        audit(confirmation.getTrip(), dispatcher, "MANUAL_CONFIRM_APPROVED", "ManualUnloadConfirmation", confirmation.getId(), values);
        return toResponse(confirmation);
    }

    @Override
    @Transactional
    public ManualConfirmationResponse rejectManualConfirmation(String tripIdOrCode, Long confirmationId, RejectManualConfirmRequest request) {
        String reason = trimmed(request.getReason());
        if (reason == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        ManualUnloadConfirmation confirmation = pendingConfirmation(tripIdOrCode, confirmationId);
        User dispatcher = currentUserService.getCurrentUser();
        CargoPackage pkg = confirmation.getCargoPackage();
        switch (confirmation.effectiveStep()) {
            // Kiện về chưa soạn: kho phải đối chiếu lại kiện này bằng nhãn
            case STAGING -> stagingConfirmationRepository
                    .deleteByTripIdAndCargoPackageId(confirmation.getTrip().getId(), pkg.getId());
            // Vị trí về chưa xếp: kho phải đối chiếu lại kiện này rồi mới xếp tiếp
            case LOADING -> {
                if (confirmation.getPlacement() != null) {
                    placementConfirmationRepository.findByPlacementId(confirmation.getPlacement().getId())
                            .ifPresent(placementConfirmationRepository::delete);
                }
                if (pkg.getStatus() == PackageStatus.LOADED) {
                    pkg.setStatus(PackageStatus.PLANNED);
                    cargoPackageRepository.save(pkg);
                }
            }
            // Kiện về chưa dỡ: tài xế phải đối chiếu lại kiện này trước khi hoàn tất điểm
            case UNLOADING -> {
                unloadConfirmationRepository.findByCargoPackageId(pkg.getId()).ifPresent(unloadConfirmationRepository::delete);
                if (pkg.getStatus() == PackageStatus.DELIVERED) {
                    pkg.setStatus(PackageStatus.LOADED);
                    cargoPackageRepository.save(pkg);
                }
            }
        }
        confirmation.setStatus(ManualConfirmStatus.REJECTED);
        confirmation.setDecidedBy(dispatcher);
        confirmation.setDecidedAt(LocalDateTime.now());
        confirmation.setDecisionNote(reason);
        manualConfirmationRepository.save(confirmation);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("tripId", confirmation.getTrip().getId());
        values.put("reason", reason);
        // Người gửi xác nhận tay: chuông của tài xế chỉ báo xác nhận của chính mình
        if (confirmation.getRequestedBy() != null) {
            values.put("requestedBy", confirmation.getRequestedBy().getId());
        }
        audit(confirmation.getTrip(), dispatcher, "MANUAL_CONFIRM_REJECTED", "ManualUnloadConfirmation", confirmation.getId(), values);
        return toResponse(confirmation);
    }

    /**
     * Xác nhận tay còn chờ của chuyến thuộc công ty người đăng nhập: của kho khi chuyến Đang xếp hàng, của tài xế khi chuyến Đang vận
     * chuyển.
     */
    private ManualUnloadConfirmation pendingConfirmation(String tripIdOrCode, Long confirmationId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, currentUserService.getCurrentCompanyId());
        ManualUnloadConfirmation confirmation = manualConfirmationRepository.findById(confirmationId)
                .filter(found -> found.getTrip() != null && trip.getId().equals(found.getTrip().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.MANUAL_CONFIRM_NOT_FOUND));
        if (confirmation.getStatus() != ManualConfirmStatus.PENDING || !stepMatches(confirmation, trip)) {
            throw new AppException(ErrorCode.MANUAL_CONFIRM_NOT_PENDING);
        }
        return confirmation;
    }

    /** Bước của xác nhận tay khớp pha của chuyến: soạn, xếp lúc Đang xếp hàng; dỡ lúc Đang vận chuyển. */
    private static boolean stepMatches(ManualUnloadConfirmation confirmation, Trip trip) {
        return confirmation.isUnloadingStep()
                ? trip.getStatus() == TripStatus.IN_TRANSIT
                : trip.getStatus() == TripStatus.LOADING;
    }

    private List<ManualUnloadConfirmation> pendingOf(DeliveryStop stop, CargoPackage pkg) {
        return manualConfirmationRepository.findByDeliveryStopIdOrderByRequestedAtAsc(stop.getId()).stream()
                .filter(ManualUnloadConfirmation::isUnloadingStep)
                .filter(entry -> entry.getStatus() == ManualConfirmStatus.PENDING)
                .filter(entry -> entry.getCargoPackage() != null && pkg.getId().equals(entry.getCargoPackage().getId()))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Chung
    // ---------------------------------------------------------------------------------------------

    private record AtStop(User driver, Trip trip, DeliveryStop stop, CargoPackage pkg) {
    }

    /**
     * Kiện của điểm đang giao (điểm chưa hoàn tất đầu tiên, tài xế đã báo đến) của chuyến Đang vận chuyển được giao cho chính tài xế
     * đang đăng nhập, cùng công ty.
     */
    private AtStop packageAtArrivedStop(Long packageId) {
        User driver = currentUserService.getCurrentUser();
        CargoPackage pkg = cargoPackageRepository.findById(packageId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
        DeliveryStop packageStop = tripPackageResolver.stopOfPackage(pkg)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));
        Trip trip = packageStop.getTrip();
        if (trip == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        // Cùng công ty và đúng tài xế của chuyến
        trip = driverService.requireOwnTrip(String.valueOf(trip.getId()));
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }
        DeliveryStop current = deliveryStopRepository
                .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(trip.getId(), DeliveryStopStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.NO_PENDING_DELIVERY_STOP));
        if (!current.getId().equals(packageStop.getId())) {
            throw new WrongStopException("Kiện hàng không thuộc điểm dừng hiện tại");
        }
        // Đã đến là giờ đến thật (arrive): trạng thái ARRIVED còn được đặt tự động cho điểm kế tiếp khi hoàn tất điểm trước
        if (current.getActualArrival() == null) {
            throw new AppException(ErrorCode.DELIVERY_STOP_NOT_ARRIVED);
        }
        return new AtStop(driver, trip, current, pkg);
    }

    private void audit(Trip trip, User user, String action, String entity, Long entityId, Map<String, Object> values) {
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(action)
                .entityName(entity)
                .entityId(String.valueOf(entityId))
                .user(user)
                .newValues(values)
                .createdAt(Instant.now())
                .build());
    }

    public static ManualConfirmationResponse toResponse(ManualUnloadConfirmation confirmation) {
        CargoPackage pkg = confirmation.getCargoPackage();
        DeliveryStop stop = confirmation.getDeliveryStop();
        Trip trip = confirmation.getTrip();
        return ManualConfirmationResponse.builder()
                .id(confirmation.getId())
                .tripId(trip != null ? trip.getId() : null)
                .tripCode(trip != null ? trip.getTripCode() : null)
                .stopId(stop != null ? stop.getId() : null)
                .stopSequence(stop != null ? stop.getStopSequence() : null)
                .stopName(stop != null ? stop.getStopName() : null)
                .packageId(pkg != null ? pkg.getId() : null)
                .packageCode(pkg != null ? pkg.getPackageCode() : null)
                .trackingBarcode(pkg != null ? pkg.getTrackingBarcode() : null)
                .packageName(packageName(pkg))
                .reason(confirmation.getReason() != null ? confirmation.getReason().name() : null)
                .note(confirmation.getNote())
                .status(confirmation.getStatus() != null ? confirmation.getStatus().name() : null)
                .requestedById(confirmation.getRequestedBy() != null ? confirmation.getRequestedBy().getId() : null)
                .requestedByName(confirmation.getRequestedBy() != null ? confirmation.getRequestedBy().getFullName() : null)
                .requestedAt(confirmation.getRequestedAt())
                .decidedByName(confirmation.getDecidedBy() != null ? confirmation.getDecidedBy().getFullName() : null)
                .decidedAt(confirmation.getDecidedAt())
                .decisionNote(confirmation.getDecisionNote())
                .step(confirmation.effectiveStep().name())
                .placementId(confirmation.getPlacement() != null ? confirmation.getPlacement().getId() : null)
                .build();
    }

    private static String packageName(CargoPackage pkg) {
        if (pkg == null) {
            return null;
        }
        if (pkg.getPackageType() != null && pkg.getPackageType().getName() != null) {
            return pkg.getPackageType().getName();
        }
        return pkg.getTrackingBarcode();
    }

    private static BigDecimal weightOf(CargoPackage pkg) {
        if (pkg == null) {
            return null;
        }
        if (pkg.getActualWeightKg() != null) {
            return pkg.getActualWeightKg();
        }
        return pkg.getPackageType() != null ? pkg.getPackageType().getWeightKg() : null;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
