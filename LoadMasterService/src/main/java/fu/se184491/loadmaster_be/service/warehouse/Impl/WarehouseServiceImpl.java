package fu.se184491.loadmaster_be.service.warehouse.Impl;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordDeviationRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.StartLoadingRequest;
import fu.se184491.loadmaster_be.dto.response.warehouse.CompleteLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.ConfirmPlacementResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DeviationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.PlacementStepResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.StartLoadingResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.WarehouseTaskResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.entity.warehouse.Deviation;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.IncompleteLoadingException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseProgress;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseServiceImpl implements WarehouseService {

    private final TripRepository tripRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final UserRepository userRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;
    private final TripStatusService tripStatusService;
    private final DeliveryStopRepository deliveryStopRepository;
    private final TripPackageResolver tripPackageResolver;
    private final WarehouseProgress warehouseProgress;
    private final LoadingDamageRepository loadingDamageRepository;

    /**
     * Nhiệm vụ của kho: chuyến Đã lập kế hoạch của công ty người đăng nhập, có phương án đã
     * duyệt và chưa bắt đầu xếp. {@code companyId}, {@code workerId} chỉ giữ cho client cũ:
     * gửi khác người đăng nhập là bị từ chối. {@code includeAwaitingReplan} thêm chuyến có phiên xếp dừng vì kiện thiếu / hỏng mà điều phối
     * viên chưa duyệt phương án mới ({@code awaitingReplan}: kho chỉ xem, không bắt đầu được).
     */
    @Override
    @Transactional(readOnly = true)
    public List<WarehouseTaskResponse> getTasks(Long companyId, Long workerId, boolean includeAwaitingReplan) {
        User worker = currentUserService.getCurrentUser();
        Long targetCompanyId = companyOf(worker);
        if ((companyId != null && !companyId.equals(targetCompanyId))
                || (workerId != null && !workerId.equals(worker.getId()))) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        List<Trip> trips = tripRepository.findByCompanyIdAndStatus(targetCompanyId, TripStatus.PLANNED);

        List<WarehouseTaskResponse> tasks = new ArrayList<>();

        for (Trip trip : trips) {
            Optional<LoadPlan> approvedPlanOpt = loadPlanRepository
                    .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId());

            if (approvedPlanOpt.isEmpty()) {
                if (includeAwaitingReplan) {
                    awaitingReplan(trip).ifPresent(tasks::add);
                }
                continue;
            }

            LoadPlan approvedPlan = approvedPlanOpt.get();
            Optional<LoadingExecution> executionOpt = loadingExecutionRepository.findByLoadPlanId(approvedPlan.getId());

            if (executionOpt.isPresent()) {
                LoadingExecutionStatus status = executionOpt.get().getStatus();
                if (status == LoadingExecutionStatus.IN_PROGRESS || status == LoadingExecutionStatus.COMPLETED) {
                    continue;
                }
            }

            tasks.add(WarehouseTaskResponse.builder()
                    .tripId(trip.getId())
                    .tripCode(trip.getTripCode())
                    .vehicleInfo(formatVehicleInfo(trip.getVehicle()))
                    .totalPackages(approvedPlan.getPackedItemsCount())
                    .loadPlanId(approvedPlan.getId())
                    .departureTime(trip.getDepartureTime())
                    .status(trip.getStatus() != null ? trip.getStatus().name() : "APPROVED")
                    .build());
        }

        // Chuyến Đang xếp hàng có phiên xếp: kho mở lại phiên bằng GET /api/warehouse/tasks/{tripId}.
        for (Trip trip : tripRepository.findByCompanyIdAndStatus(targetCompanyId, TripStatus.LOADING)) {
            Optional<LoadPlan> approvedPlanOpt = loadPlanRepository
                    .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId());
            Optional<LoadingExecution> executionOpt = approvedPlanOpt
                    .flatMap(plan -> loadingExecutionRepository.findByLoadPlanId(plan.getId()));
            if (executionOpt.isEmpty()) {
                continue;
            }
            LoadingExecution execution = executionOpt.get();
            tasks.add(WarehouseTaskResponse.builder()
                    .tripId(trip.getId())
                    .tripCode(trip.getTripCode())
                    .vehicleInfo(formatVehicleInfo(trip.getVehicle()))
                    .totalPackages(approvedPlanOpt.get().getPackedItemsCount())
                    .loadPlanId(approvedPlanOpt.get().getId())
                    .departureTime(trip.getDepartureTime())
                    .status(TripStatus.LOADING.name())
                    .executionId(execution.getId())
                    .executionStatus(execution.getStatus() != null ? execution.getStatus().name() : null)
                    .build());
        }

        // Bỏ yêu cầu giao vừa hết kiện đưa chuyến về Nháp: vẫn chờ điều phối viên tối ưu lại.
        if (includeAwaitingReplan) {
            for (Trip trip : tripRepository.findByCompanyIdAndStatus(targetCompanyId, TripStatus.DRAFT)) {
                awaitingReplan(trip).ifPresent(tasks::add);
            }
        }

        log.info("Found {} warehouse tasks for companyId {}", tasks.size(), targetCompanyId);
        return tasks;
    }

    /** Chuyến chưa có phương án đã duyệt mà phiên xếp gần nhất dừng vì kiện thiếu / hỏng: chờ điều phối viên tối ưu lại. */
    private Optional<WarehouseTaskResponse> awaitingReplan(Trip trip) {
        return loadingExecutionRepository.findAllByLoadPlanJobTripId(trip.getId()).stream()
                .max(java.util.Comparator.comparing(LoadingExecution::getId))
                .filter(execution -> execution.getStatus() == LoadingExecutionStatus.ABORTED && execution.getAbortReason() != null)
                .map(execution -> WarehouseTaskResponse.builder()
                        .tripId(trip.getId())
                        .tripCode(trip.getTripCode())
                        .vehicleInfo(formatVehicleInfo(trip.getVehicle()))
                        .departureTime(trip.getDepartureTime())
                        .status(trip.getStatus() != null ? trip.getStatus().name() : null)
                        .awaitingReplan(true)
                        .replanReason(execution.getAbortReason().name())
                        .build());
    }

    @Override
    @Transactional
    public StartLoadingResponse startLoading(Long tripId, StartLoadingRequest request) {
        User worker = currentUserService.getCurrentUser();
        if (request != null && request.getWorkerId() != null && !request.getWorkerId().equals(worker.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        Trip trip = requireCompanyTrip(tripId, worker);
        if (trip.getStatus() == TripStatus.LOADING) {
            throw new AppException(ErrorCode.TRIP_ALREADY_LOADING);
        }
        if (trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_NOT_READY_FOR_LOADING);
        }

        LoadPlan approvedPlan = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.NO_APPROVED_LOAD_PLAN));

        // Mỗi phương án chỉ có một phiên xếp (load_plan_id là duy nhất). Phiên đã dừng của chính phương án này được mở lại từ đầu.
        Optional<LoadingExecution> existing = loadingExecutionRepository.findByLoadPlanId(approvedPlan.getId());
        if (existing.isPresent() && existing.get().getStatus() != LoadingExecutionStatus.ABORTED) {
            throw new AppException(ErrorCode.TRIP_ALREADY_LOADING);
        }
        boolean staging = request != null && Boolean.TRUE.equals(request.getStaging());
        LoadingExecution execution = existing.isPresent()
                ? reopen(existing.get(), worker, staging)
                : LoadingExecution.builder()
                .loadPlan(approvedPlan)
                .worker(worker)
                .status(LoadingExecutionStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .totalDeviations(0)
                .stagingRequired(staging)
                .build();

        execution = loadingExecutionRepository.save(execution);
        tripStatusService.transitionTo(tripId, TripStatus.LOADING, worker.getId());

        StartLoadingResponse response = toSession(trip, approvedPlan, execution);
        log.info("Started loading execution {} for trip {} with {} placements",
                execution.getId(), trip.getTripCode(), response.getTotalPackages());
        return response;
    }

    /** Phiên đã dừng của phương án đang duyệt bắt đầu lại từ đầu: bỏ các lần xác nhận vị trí, sai lệch, kiện hỏng của nó. */
    private LoadingExecution reopen(LoadingExecution execution, User worker, boolean staging) {
        Long planId = execution.getLoadPlan().getId();
        placementConfirmationRepository.deleteAll(placementConfirmationRepository.findByPlacementLoadPlanId(planId));
        loadingDamageRepository.deleteAll(loadingDamageRepository.findByPlacementLoadPlanId(planId));
        deviationRepository.deleteAll(deviationRepository.findByPlacementLoadPlanId(planId));
        execution.setWorker(worker);
        execution.setStatus(LoadingExecutionStatus.IN_PROGRESS);
        execution.setStartedAt(LocalDateTime.now());
        execution.setCompletedAt(null);
        execution.setTotalDeviations(0);
        execution.setStagingRequired(staging);
        execution.setSealNumber(null);
        execution.setSealedAt(null);
        execution.setSealedBy(null);
        execution.setAbortReason(null);
        return execution;
    }

    /**
     * Mở lại phiên xếp của chuyến Đang xếp hàng (đang dở hoặc đã xếp xong — chờ xuất phát), kèm
     * trạng thái từng kiện để kho tiếp tục ở kiện chưa xử lý đầu tiên.
     */
    @Override
    @Transactional(readOnly = true)
    public StartLoadingResponse getSession(Long tripId) {
        User worker = currentUserService.getCurrentUser();
        Trip trip = requireCompanyTrip(tripId, worker);
        if (trip.getStatus() != TripStatus.LOADING) {
            throw new AppException(ErrorCode.TRIP_NOT_LOADING);
        }
        LoadPlan approvedPlan = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));
        LoadingExecution execution = loadingExecutionRepository.findByLoadPlanId(approvedPlan.getId())
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));
        return toSession(trip, approvedPlan, execution);
    }

    private StartLoadingResponse toSession(Trip trip, LoadPlan plan, LoadingExecution execution) {
        List<PackagePlacement> placements = packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(plan.getId());
        Set<Long> confirmed = placementConfirmationRepository.findByPlacementLoadPlanId(plan.getId()).stream()
                .map(confirmation -> confirmation.getPlacement() != null ? confirmation.getPlacement().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> deviated = deviationRepository.findByPlacementLoadPlanId(plan.getId()).stream()
                .map(deviation -> deviation.getPlacement() != null ? deviation.getPlacement().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DeliveryStop> stops = stopsByPackage(trip.getId());
        WarehouseProgress.State state = warehouseProgress.of(trip.getId(), plan.getId());

        List<PlacementStepResponse> steps = new ArrayList<>();
        int confirmedCount = 0;
        int deviationCount = 0;
        int damagedCount = 0;
        for (PackagePlacement placement : placements) {
            String status;
            if (state.leftOut().contains(placement.getId())) {
                status = PlacementStepResponse.STATUS_DAMAGED;
                damagedCount++;
            } else if (confirmed.contains(placement.getId())) {
                status = PlacementStepResponse.STATUS_CONFIRMED;
                confirmedCount++;
            } else if (deviated.contains(placement.getId())) {
                status = PlacementStepResponse.STATUS_DEVIATION;
                deviationCount++;
            } else {
                status = PlacementStepResponse.STATUS_PENDING;
            }
            DeliveryStop stop = placement.getCargoPackage() != null
                    ? stops.get(placement.getCargoPackage().getId())
                    : null;
            PlacementStepResponse step = mapToStepResponse(placement, stop != null ? stop : placement.getStopZone(), status);
            withProgress(step, placement, state);
            steps.add(step);
        }
        boolean stagingRequired = Boolean.TRUE.equals(execution.getStagingRequired());

        Vehicle vehicle = trip.getVehicle();
        VehicleType type = vehicle != null ? vehicle.getVehicleType() : null;
        return StartLoadingResponse.builder()
                .executionId(execution.getId())
                .loadPlanId(plan.getId())
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .vehicleInfo(formatVehicleInfo(vehicle))
                .licensePlate(vehicle != null ? vehicle.getLicensePlate() : null)
                .vehicleTypeName(type != null ? type.getName() : null)
                .innerLength(type != null ? type.getInnerLength() : null)
                .innerWidth(type != null ? type.getInnerWidth() : null)
                .innerHeight(type != null ? type.getInnerHeight() : null)
                .doorWidth(type != null ? type.getDoorWidth() : null)
                .doorHeight(type != null ? type.getDoorHeight() : null)
                .maxPayloadKg(type != null ? type.getMaxPayloadKg() : null)
                .totalPackages(steps.size())
                .confirmedCount(confirmedCount)
                .deviationCount(deviationCount)
                .pendingCount(steps.size() - confirmedCount - deviationCount - damagedCount)
                .status(execution.getStatus() != null ? execution.getStatus().name() : null)
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .placements(steps)
                .stagingRequired(stagingRequired)
                .stagingComplete(!stagingRequired || state.stagingComplete(placements))
                .stagedCount(state.stagedCount(placements))
                .openShortageCount(state.openShortages())
                .damagedCount(damagedCount)
                .pendingManualCount(state.pendingManual())
                .sealNumber(execution.getSealNumber())
                .sealedAt(execution.getSealedAt())
                .sealedByName(execution.getSealedBy() != null ? execution.getSealedBy().getFullName() : null)
                .build();
    }

    /** Soạn hàng, báo thiếu và xác nhận tay của kho với kiện của vị trí này. */
    private static void withProgress(PlacementStepResponse step, PackagePlacement placement, WarehouseProgress.State state) {
        Long packageId = placement.getCargoPackage() != null ? placement.getCargoPackage().getId() : null;
        if (packageId == null) {
            return;
        }
        var staged = state.stagedByPackage().get(packageId);
        step.setStaged(staged != null);
        step.setStagingMethod(staged != null && staged.getMethod() != null ? staged.getMethod().name() : null);
        var shortage = state.shortageByPackage().get(packageId);
        step.setShortageStatus(shortage != null && shortage.getStatus() != null ? shortage.getStatus().name() : null);
        var manual = state.manualByPackage().get(packageId);
        if (manual != null) {
            step.setManualStatus(manual.getStatus() != null ? manual.getStatus().name() : null);
            step.setManualStep(manual.effectiveStep().name());
            step.setManualRejectReason(manual.getDecisionNote());
        }
    }

    /** Điểm giao thật của từng kiện trong chuyến, đọc qua yêu cầu giao của từng điểm. */
    private Map<Long, DeliveryStop> stopsByPackage(Long tripId) {
        Map<Long, DeliveryStop> byPackage = new HashMap<>();
        for (DeliveryStop stop : deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(tripId)) {
            for (CargoPackage cargoPackage : tripPackageResolver.packagesOfStop(stop.getId())) {
                byPackage.putIfAbsent(cargoPackage.getId(), stop);
            }
        }
        return byPackage;
    }

    @Override
    @Transactional
    public ConfirmPlacementResponse confirmPlacement(Long placementId, Long workerId) {
        User worker = currentUserService.getCurrentUser();
        if (workerId != null && !workerId.equals(worker.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        PackagePlacement placement = requireLoadingPlacement(placementId, worker);

        if (placementConfirmationRepository.existsByPlacementId(placementId)) {
            throw new AppException(ErrorCode.PLACEMENT_ALREADY_CONFIRMED);
        }

        List<PackagePlacement> allPlacements = packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(placement.getLoadPlan().getId());
        requireStaged(placement, allPlacements);
        requireInSequence(placement, allPlacements);

        PlacementConfirmation confirmation = PlacementConfirmation.builder()
                .placement(placement)
                .confirmedBy(worker)
                .confirmedAt(LocalDateTime.now())
                .build();

        confirmation = placementConfirmationRepository.save(confirmation);
        markLoaded(placement);

        // Audit log WAREHOUSE_CONFIRM
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("placementId", placement.getId());
        newValues.put("confirmed", true);
        newValues.put("confirmedAt", confirmation.getConfirmedAt().toString());

        AuditLog auditLog = AuditLog.builder()
                .companyId(AuditCompany.ofPlan(placement.getLoadPlan()))
                .actionType("WAREHOUSE_CONFIRM")
                .entityName("PlacementConfirmation")
                .entityId(String.valueOf(confirmation.getId()))
                .user(worker)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);

        // Find next placement in sequence
        PlacementStepResponse nextPlacement = findNextPlacement(allPlacements, placement.getStepSequence());

        log.info("Confirmed placement {} for load plan {}", placementId, placement.getLoadPlan().getId());

        return ConfirmPlacementResponse.builder()
                .placementId(placement.getId())
                .status("CONFIRMED")
                .confirmedAt(confirmation.getConfirmedAt())
                .nextPlacement(nextPlacement)
                .build();
    }

    @Override
    @Transactional
    public DeviationResponse recordDeviation(Long placementId, RecordDeviationRequest request) {
        User reporter = currentUserService.getCurrentUser();
        if (request.getReportedBy() != null && !request.getReportedBy().equals(reporter.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        PackagePlacement placement = requireLoadingPlacement(placementId, reporter);

        if (deviationRepository.existsByPlacementId(placementId)) {
            throw new AppException(ErrorCode.DEVIATION_ALREADY_RECORDED);
        }
        requireStaged(placement, packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(placement.getLoadPlan().getId()));

        Deviation deviation = Deviation.builder()
                .placement(placement)
                .actualPosX(request.getActualPosX())
                .actualPosY(request.getActualPosY())
                .actualPosZ(request.getActualPosZ())
                .reason(request.getReason())
                .reportedBy(reporter)
                .reportedAt(LocalDateTime.now())
                .build();

        deviation = deviationRepository.save(deviation);
        // Sai lệch vị trí vẫn là kiện đã lên xe.
        markLoaded(placement);

        // Increment LoadingExecution.totalDeviations
        int totalDeviations = 1;
        Optional<LoadingExecution> executionOpt = loadingExecutionRepository.findByLoadPlanId(placement.getLoadPlan().getId());
        if (executionOpt.isPresent()) {
            LoadingExecution exec = executionOpt.get();
            int currentCount = exec.getTotalDeviations() != null ? exec.getTotalDeviations() : 0;
            totalDeviations = currentCount + 1;
            exec.setTotalDeviations(totalDeviations);
            loadingExecutionRepository.save(exec);
        }

        // Audit log DEVIATION_RECORDED
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("placementId", placement.getId());
        newValues.put("actualPosX", request.getActualPosX());
        newValues.put("actualPosY", request.getActualPosY());
        newValues.put("actualPosZ", request.getActualPosZ());
        newValues.put("reason", request.getReason());

        AuditLog auditLog = AuditLog.builder()
                .companyId(AuditCompany.ofPlan(placement.getLoadPlan()))
                .actionType("DEVIATION_RECORDED")
                .entityName("Deviation")
                .entityId(String.valueOf(deviation.getId()))
                .user(reporter)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);

        List<PackagePlacement> allPlacements = packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(placement.getLoadPlan().getId());
        PlacementStepResponse nextPlacement = findNextPlacement(allPlacements, placement.getStepSequence());

        log.info("Recorded deviation for placement {} (total deviations: {})", placementId, totalDeviations);

        return DeviationResponse.builder()
                .deviationId(deviation.getId())
                .placementId(placement.getId())
                .actualPosX(deviation.getActualPosX())
                .actualPosY(deviation.getActualPosY())
                .actualPosZ(deviation.getActualPosZ())
                .reason(deviation.getReason())
                .totalDeviations(totalDeviations)
                .reportedAt(deviation.getReportedAt())
                .nextPlacement(nextPlacement)
                .build();
    }

    @Override
    @Transactional
    public CompleteLoadingResponse completeLoading(Long tripId) {
        User worker = currentUserService.getCurrentUser();
        Trip trip = requireCompanyTrip(tripId, worker);
        if (trip.getStatus() != TripStatus.LOADING) {
            throw new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND);
        }

        LoadPlan approvedPlan = loadPlanRepository
                .findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.NO_APPROVED_LOAD_PLAN));

        LoadingExecution execution = loadingExecutionRepository.findByLoadPlanId(approvedPlan.getId())
                .filter(found -> found.getStatus() == LoadingExecutionStatus.IN_PROGRESS)
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));

        List<PackagePlacement> placements = packagePlacementRepository.findByLoadPlanId(approvedPlan.getId());

        List<Long> unhandledPlacementIds = new ArrayList<>();
        List<PackagePlacement> loaded = new ArrayList<>();
        for (PackagePlacement p : placements) {
            if (warehouseProgress.isLeftOut(p.getId())) {
                continue;
            }
            loaded.add(p);
            boolean handled = placementConfirmationRepository.existsByPlacementId(p.getId())
                    || deviationRepository.existsByPlacementId(p.getId());
            if (!handled) {
                unhandledPlacementIds.add(p.getId());
            }
        }

        if (!unhandledPlacementIds.isEmpty()) {
            throw new IncompleteLoadingException("Quá trình bốc xếp chưa hoàn tất, còn "
                    + unhandledPlacementIds.size() + " kiện hàng chưa được xác nhận hoặc ghi nhận sai lệch");
        }
        WarehouseProgress.State state = warehouseProgress.of(tripId, approvedPlan.getId());
        if (state != null && state.pendingManual() > 0) {
            throw new AppException(ErrorCode.LOADING_MANUAL_CONFIRM_PENDING);
        }

        execution.setStatus(LoadingExecutionStatus.COMPLETED);
        execution.setCompletedAt(LocalDateTime.now());
        loadingExecutionRepository.save(execution);
        loaded.forEach(this::markLoaded);

        // Chuyến ở lại Đang xếp hàng (xếp xong — chờ xuất phát); tài xế bấm xuất phát mới sang
        // Đang vận chuyển (POST /api/driver/trips/{id}/start).

        // Audit log LOADING_COMPLETED
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("tripId", trip.getId());
        newValues.put("status", "COMPLETED");
        newValues.put("tripStatus", trip.getStatus().name());
        newValues.put("completedAt", execution.getCompletedAt().toString());

        AuditLog auditLog = AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("LOADING_COMPLETED")
                .entityName("LoadingExecution")
                .entityId(String.valueOf(execution.getId()))
                .user(worker)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);

        log.info("Completed loading execution {} for trip {}", execution.getId(), trip.getTripCode());

        return CompleteLoadingResponse.builder()
                .executionId(execution.getId())
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .status(execution.getStatus().name())
                .tripStatus(trip.getStatus().name())
                .totalPackages(loaded.size())
                .totalDeviations(execution.getTotalDeviations() != null ? execution.getTotalDeviations() : 0)
                .completedAt(execution.getCompletedAt())
                .build();
    }

    private PlacementStepResponse findNextPlacement(List<PackagePlacement> allPlacements, Integer currentSequence) {
        return allPlacements.stream()
                .filter(p -> p.getStepSequence() > currentSequence)
                .filter(p -> !handled(p))
                .findFirst()
                .map(p -> mapToStepResponse(p, stopOf(p), PlacementStepResponse.STATUS_PENDING))
                .orElse(null);
    }

    /** Vị trí đã xử lý: đã xác nhận, đã ghi sai lệch, hoặc là kiện hỏng bị bỏ lại kho. */
    private boolean handled(PackagePlacement placement) {
        return placementConfirmationRepository.existsByPlacementId(placement.getId())
                || deviationRepository.existsByPlacementId(placement.getId())
                || warehouseProgress.isLeftOut(placement.getId());
    }

    /** Mọi vị trí đứng trước vị trí này theo thứ tự xếp phải đã xử lý. */
    private void requireInSequence(PackagePlacement placement, List<PackagePlacement> allPlacements) {
        for (PackagePlacement p : allPlacements) {
            if (p.getStepSequence() < placement.getStepSequence() && !handled(p)) {
                throw new AppException(ErrorCode.INVALID_SEQUENCE_ORDER);
            }
        }
    }

    /** Phiên có bước soạn hàng: xếp chỉ sau khi soạn đủ. */
    private void requireStaged(PackagePlacement placement, List<PackagePlacement> allPlacements) {
        Trip trip = placement.getLoadPlan().getJob().getTrip();
        loadingExecutionRepository.findByLoadPlanId(placement.getLoadPlan().getId())
                .ifPresent(execution -> warehouseProgress.requireStagingComplete(execution, trip.getId(), allPlacements));
    }

    private DeliveryStop stopOf(PackagePlacement placement) {
        if (placement.getCargoPackage() != null) {
            Optional<DeliveryStop> stop = tripPackageResolver.stopOfPackage(placement.getCargoPackage());
            if (stop.isPresent()) {
                return stop.get();
            }
        }
        return placement.getStopZone();
    }

    private PlacementStepResponse mapToStepResponse(PackagePlacement p, DeliveryStop stop, String status) {
        String packageName = null;
        String barcode = null;
        String packageCode = null;
        String qrToken = null;
        Long packageId = null;
        BigDecimal weightKg = null;

        if (p.getCargoPackage() != null) {
            packageId = p.getCargoPackage().getId();
            barcode = p.getCargoPackage().getTrackingBarcode();
            packageCode = p.getCargoPackage().getPackageCode();
            qrToken = p.getCargoPackage().getQrToken();
            weightKg = p.getCargoPackage().getActualWeightKg();
            if (p.getCargoPackage().getPackageType() != null) {
                packageName = p.getCargoPackage().getPackageType().getName();
                if (weightKg == null) {
                    weightKg = p.getCargoPackage().getPackageType().getWeightKg();
                }
            }
        }
        if (packageName == null) {
            packageName = barcode != null ? barcode : "Kiện hàng #" + p.getId();
        }

        return PlacementStepResponse.builder()
                .placementId(p.getId())
                .packageId(packageId)
                .packageName(packageName)
                .packageCode(packageCode)
                .trackingBarcode(barcode)
                .qrToken(qrToken)
                .posX(p.getPosX())
                .posY(p.getPosY())
                .posZ(p.getPosZ())
                .packedLength(p.getPackedLength())
                .packedWidth(p.getPackedWidth())
                .packedHeight(p.getPackedHeight())
                .rotation(p.getRotationType())
                .weightKg(weightKg)
                .sequence(p.getStepSequence())
                .stopId(stop != null ? stop.getId() : null)
                .stopSequence(stop != null ? stop.getStopSequence() : null)
                .stopName(stop != null ? stop.getStopName() : null)
                .status(status)
                .build();
    }

    private Long companyOf(User user) {
        if (user.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        return user.getCompany().getId();
    }

    private Trip requireCompanyTrip(Long tripId, User user) {
        return tripRepository.findByIdAndCompanyId(tripId, companyOf(user))
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }

    /** Vị trí kiện thuộc chuyến của công ty, chuyến đang xếp và phiên xếp đang mở. */
    private PackagePlacement requireLoadingPlacement(Long placementId, User user) {
        PackagePlacement placement = packagePlacementRepository.findById(placementId)
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));
        Trip trip = placement.getLoadPlan() != null && placement.getLoadPlan().getJob() != null
                ? placement.getLoadPlan().getJob().getTrip()
                : null;
        if (trip == null || trip.getCompany() == null
                || !trip.getCompany().getId().equals(companyOf(user))) {
            throw new AppException(ErrorCode.PLACEMENT_NOT_FOUND);
        }
        boolean loading = trip.getStatus() == TripStatus.LOADING
                && loadingExecutionRepository.existsByLoadPlanIdAndStatus(
                        placement.getLoadPlan().getId(), LoadingExecutionStatus.IN_PROGRESS);
        if (!loading) {
            throw new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND);
        }
        return placement;
    }

    private void markLoaded(PackagePlacement placement) {
        CargoPackage cargoPackage = placement.getCargoPackage();
        if (cargoPackage != null
                && (cargoPackage.getStatus() == PackageStatus.PENDING
                || cargoPackage.getStatus() == PackageStatus.PLANNED)) {
            cargoPackage.setStatus(PackageStatus.LOADED);
        }
    }

    private String formatVehicleInfo(Vehicle vehicle) {
        if (vehicle == null) {
            return "Chưa gán xe";
        }
        String typeName = vehicle.getVehicleType() != null ? vehicle.getVehicleType().getName() : "Không rõ loại";
        return vehicle.getLicensePlate() + " (" + typeName + ")";
    }
}
