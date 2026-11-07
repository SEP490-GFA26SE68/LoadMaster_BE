package fu.se184491.loadmaster_be.service.warehouse.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageDecision;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ResolveShortageRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StagePackageRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StagingShortageResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.warehouse.LoadingReplanService;
import fu.se184491.loadmaster_be.service.warehouse.StagingRecorder;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseProgress;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup.Session;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseStagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseStagingServiceImpl implements WarehouseStagingService {

    private final WarehouseSessionLookup lookup;
    private final WarehouseProgress warehouseProgress;
    private final StagingConfirmationRepository stagingConfirmationRepository;
    private final StagingShortageRepository stagingShortageRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final TripLookupHelper tripLookupHelper;
    private final TripPackageResolver tripPackageResolver;
    private final CurrentUserService currentUserService;
    private final LoadingReplanService loadingReplanService;
    private final StagingRecorder stagingRecorder;

    @Override
    @Transactional
    public StagingResponse stage(Long tripId, Long packageId, StagePackageRequest request) {
        StagingMethod method = request != null && request.getMethod() != null ? request.getMethod() : StagingMethod.QR;
        if (method == StagingMethod.MANUAL) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        Session session = lookup.activeSession(tripId);
        PackagePlacement placement = lookup.placementOfPackage(session, packageId);
        if (stagingConfirmationRepository.findByTripIdAndCargoPackageId(tripId, packageId).isPresent()) {
            return response(session, packageId, method, true);
        }
        stagingRecorder.dropPendingManual(tripId, packageId);
        stagingRecorder.record(session.trip(), placement.getCargoPackage(), method, session.worker(), session.worker());
        log.info("Worker {} staged package {} for trip {} by {}", session.worker().getId(), packageId, tripId, method);
        return response(session, packageId, method, false);
    }

    @Override
    @Transactional
    public StagingResponse stageManually(Long tripId, Long packageId, ManualUnloadRequest request) {
        String note = WarehouseSessionLookup.trimmed(request.getNote());
        if (request.getReason() == ManualConfirmReason.OTHER && note == null) {
            throw new AppException(ErrorCode.MANUAL_CONFIRM_NOTE_REQUIRED);
        }
        Session session = lookup.activeSession(tripId);
        PackagePlacement placement = lookup.placementOfPackage(session, packageId);
        if (stagingConfirmationRepository.findByTripIdAndCargoPackageId(tripId, packageId).isPresent()) {
            throw new AppException(ErrorCode.PACKAGE_ALREADY_STAGED);
        }
        DeliveryStop stop = lookup.stopOf(placement);
        if (stop == null) {
            throw new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND);
        }
        CargoPackage pkg = placement.getCargoPackage();
        // Xác nhận tay chỉ tính là đã soạn khi điều phối viên duyệt; gửi lại thì thay xác nhận tay đang chờ.
        stagingRecorder.dropPendingManual(tripId, packageId);
        ManualUnloadConfirmation confirmation = manualConfirmationRepository.save(ManualUnloadConfirmation.builder()
                .trip(session.trip())
                .deliveryStop(stop)
                .cargoPackage(pkg)
                .reason(request.getReason())
                .note(note)
                .status(ManualConfirmStatus.PENDING)
                .step(ManualConfirmStep.STAGING)
                .requestedBy(session.worker())
                .requestedAt(LocalDateTime.now())
                .build());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("tripId", session.trip().getId());
        values.put("step", ManualConfirmStep.STAGING.name());
        values.put("reason", request.getReason().name());
        lookup.audit(session.trip(), session.worker(), "MANUAL_CONFIRM_REQUESTED", "ManualUnloadConfirmation",
                confirmation.getId(), values);
        return response(session, packageId, StagingMethod.MANUAL, false);
    }

    @Override
    @Transactional
    public StagingShortageResponse reportShortage(Long tripId, Long packageId, ReportShortageRequest request) {
        Session session = lookup.activeSession(tripId);
        PackagePlacement placement = lookup.placementOfPackage(session, packageId);
        if (stagingConfirmationRepository.findByTripIdAndCargoPackageId(tripId, packageId).isPresent()) {
            throw new AppException(ErrorCode.PACKAGE_ALREADY_STAGED);
        }
        StagingShortage open = stagingShortageRepository
                .findFirstByTripIdAndCargoPackageIdAndStatus(tripId, packageId, ShortageStatus.OPEN)
                .orElse(null);
        if (open != null) {
            return toResponse(open, lookup.stopOf(placement));
        }
        StagingShortage shortage = stagingShortageRepository.save(StagingShortage.builder()
                .trip(session.trip())
                .cargoPackage(placement.getCargoPackage())
                .status(ShortageStatus.OPEN)
                .note(request != null ? WarehouseSessionLookup.trimmed(request.getNote()) : null)
                .reportedBy(session.worker())
                .reportedAt(LocalDateTime.now())
                .build());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", packageId);
        values.put("tripId", tripId);
        lookup.audit(session.trip(), session.worker(), "STAGING_SHORTAGE_REPORTED", "StagingShortage", shortage.getId(), values);
        log.info("Worker {} reported package {} missing for trip {}", session.worker().getId(), packageId, tripId);
        return toResponse(shortage, lookup.stopOf(placement));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StagingShortageResponse> listOpenShortages(String tripIdOrCode) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, currentUserService.getCurrentCompanyId());
        if (trip.getStatus() != TripStatus.LOADING) {
            return List.of();
        }
        return stagingShortageRepository.findByTripIdAndStatusOrderByReportedAtAsc(trip.getId(), ShortageStatus.OPEN).stream()
                .map(entry -> toResponse(entry, stopOfPackage(entry.getCargoPackage())))
                .toList();
    }

    @Override
    @Transactional
    public StagingShortageResponse resolveShortage(String tripIdOrCode, Long shortageId, ResolveShortageRequest request) {
        User dispatcher = currentUserService.getCurrentUser();
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, currentUserService.getCurrentCompanyId());
        StagingShortage shortage = stagingShortageRepository.findById(shortageId)
                .filter(found -> found.getTrip() != null && trip.getId().equals(found.getTrip().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.STAGING_SHORTAGE_NOT_FOUND));
        if (shortage.getStatus() != ShortageStatus.OPEN || trip.getStatus() != TripStatus.LOADING) {
            throw new AppException(ErrorCode.STAGING_SHORTAGE_NOT_OPEN);
        }
        // Điểm giao đọc trước khi kiện rời chuyến
        DeliveryStop stop = stopOfPackage(shortage.getCargoPackage());
        shortage.setResolvedBy(dispatcher);
        shortage.setResolvedAt(LocalDateTime.now());
        if (request.getDecision() == ShortageDecision.KEEP_SEARCHING) {
            shortage.setStatus(ShortageStatus.KEEP_SEARCHING);
            stagingShortageRepository.save(shortage);
        } else {
            shortage.setStatus(ShortageStatus.DROPPED);
            stagingShortageRepository.save(shortage);
            loadingReplanService.returnToPlanning(trip, activeExecution(trip), shortage.getCargoPackage(),
                    PackageFlag.NOT_FOUND, dispatcher);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", shortage.getCargoPackage() != null ? shortage.getCargoPackage().getId() : null);
        values.put("tripId", trip.getId());
        values.put("decision", request.getDecision().name());
        lookup.audit(trip, dispatcher, "STAGING_SHORTAGE_RESOLVED", "StagingShortage", shortage.getId(), values);
        return toResponse(shortage, stop);
    }

    private LoadingExecution activeExecution(Trip trip) {
        LoadPlan plan = loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));
        return loadingExecutionRepository.findByLoadPlanId(plan.getId())
                .filter(found -> found.getStatus() == LoadingExecutionStatus.IN_PROGRESS)
                .orElseThrow(() -> new AppException(ErrorCode.STAGING_SHORTAGE_NOT_OPEN));
    }

    private StagingResponse response(Session session, Long packageId, StagingMethod method, boolean alreadyStaged) {
        WarehouseProgress.State state = warehouseProgress.of(session.trip().getId(), session.plan().getId());
        List<PackagePlacement> placements = session.placements();
        StagingMethod recorded = state.stagedByPackage().containsKey(packageId)
                ? state.stagedByPackage().get(packageId).getMethod()
                : method;
        return StagingResponse.builder()
                .tripId(session.trip().getId())
                .packageId(packageId)
                .alreadyStaged(alreadyStaged)
                .method(recorded != null ? recorded.name() : null)
                .stagedCount(state.stagedCount(placements))
                .totalCount((int) placements.stream().filter(entry -> entry.getCargoPackage() != null).count())
                .stagingComplete(state.stagingComplete(placements))
                .build();
    }

    private DeliveryStop stopOfPackage(CargoPackage pkg) {
        return pkg != null ? tripPackageResolver.stopOfPackage(pkg).orElse(null) : null;
    }

    static StagingShortageResponse toResponse(StagingShortage shortage, DeliveryStop stop) {
        CargoPackage pkg = shortage.getCargoPackage();
        Trip trip = shortage.getTrip();
        return StagingShortageResponse.builder()
                .id(shortage.getId())
                .tripId(trip != null ? trip.getId() : null)
                .tripCode(trip != null ? trip.getTripCode() : null)
                .packageId(pkg != null ? pkg.getId() : null)
                .packageCode(pkg != null ? pkg.getPackageCode() : null)
                .trackingBarcode(pkg != null ? pkg.getTrackingBarcode() : null)
                .packageName(pkg == null ? null
                        : pkg.getPackageType() != null && pkg.getPackageType().getName() != null
                        ? pkg.getPackageType().getName() : pkg.getTrackingBarcode())
                .stopId(stop != null ? stop.getId() : null)
                .stopSequence(stop != null ? stop.getStopSequence() : null)
                .stopName(stop != null ? stop.getStopName() : null)
                .status(shortage.getStatus() != null ? shortage.getStatus().name() : null)
                .note(shortage.getNote())
                .reportedByName(shortage.getReportedBy() != null ? shortage.getReportedBy().getFullName() : null)
                .reportedAt(shortage.getReportedAt())
                .resolvedByName(shortage.getResolvedBy() != null ? shortage.getResolvedBy().getFullName() : null)
                .resolvedAt(shortage.getResolvedAt())
                .tripStatus(trip != null && trip.getStatus() != null ? trip.getStatus().name() : null)
                .build();
    }
}
