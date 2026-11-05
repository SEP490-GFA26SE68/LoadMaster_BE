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
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
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
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.trip.TripStatusService;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Nhiệm vụ của kho: chuyến Đã lập kế hoạch của công ty người đăng nhập, có phương án đã
     * duyệt và chưa bắt đầu xếp. {@code companyId}, {@code workerId} chỉ giữ cho client cũ:
     * gửi khác người đăng nhập là bị từ chối.
     */
    @Override
    @Transactional(readOnly = true)
    public List<WarehouseTaskResponse> getTasks(Long companyId, Long workerId) {
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

        log.info("Found {} warehouse tasks for companyId {}", tasks.size(), targetCompanyId);
        return tasks;
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

        // Mỗi phương án chỉ có một phiên xếp (load_plan_id là duy nhất).
        if (loadingExecutionRepository.findByLoadPlanId(approvedPlan.getId()).isPresent()) {
            throw new AppException(ErrorCode.TRIP_ALREADY_LOADING);
        }

        LoadingExecution execution = LoadingExecution.builder()
                .loadPlan(approvedPlan)
                .worker(worker)
                .status(LoadingExecutionStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .totalDeviations(0)
                .build();

        execution = loadingExecutionRepository.save(execution);
        tripStatusService.transitionTo(tripId, TripStatus.LOADING, worker.getId());

        List<PackagePlacement> placements = packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(approvedPlan.getId());

        List<PlacementStepResponse> placementDtos = placements.stream()
                .map(this::mapToStepResponse)
                .toList();

        log.info("Started loading execution {} for trip {} with {} placements",
                execution.getId(), trip.getTripCode(), placementDtos.size());

        return StartLoadingResponse.builder()
                .executionId(execution.getId())
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .vehicleInfo(formatVehicleInfo(trip.getVehicle()))
                .totalPackages(placementDtos.size())
                .status(execution.getStatus().name())
                .startedAt(execution.getStartedAt())
                .placements(placementDtos)
                .build();
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

        // Verify sequence order: all placements with stepSequence < this placement's stepSequence must be handled
        List<PackagePlacement> allPlacements = packagePlacementRepository
                .findByLoadPlanIdOrderByStepSequenceAsc(placement.getLoadPlan().getId());

        for (PackagePlacement p : allPlacements) {
            if (p.getStepSequence() < placement.getStepSequence()) {
                boolean isHandled = placementConfirmationRepository.existsByPlacementId(p.getId())
                        || deviationRepository.existsByPlacementId(p.getId());
                if (!isHandled) {
                    throw new AppException(ErrorCode.INVALID_SEQUENCE_ORDER);
                }
            }
        }

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
        for (PackagePlacement p : placements) {
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

        execution.setStatus(LoadingExecutionStatus.COMPLETED);
        execution.setCompletedAt(LocalDateTime.now());
        loadingExecutionRepository.save(execution);
        placements.forEach(this::markLoaded);

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
                .totalPackages(placements.size())
                .totalDeviations(execution.getTotalDeviations() != null ? execution.getTotalDeviations() : 0)
                .completedAt(execution.getCompletedAt())
                .build();
    }

    private PlacementStepResponse findNextPlacement(List<PackagePlacement> allPlacements, Integer currentSequence) {
        return allPlacements.stream()
                .filter(p -> p.getStepSequence() > currentSequence)
                .filter(p -> !placementConfirmationRepository.existsByPlacementId(p.getId())
                        && !deviationRepository.existsByPlacementId(p.getId()))
                .findFirst()
                .map(this::mapToStepResponse)
                .orElse(null);
    }

    private PlacementStepResponse mapToStepResponse(PackagePlacement p) {
        String packageName = null;
        String barcode = null;
        Long packageId = null;

        if (p.getCargoPackage() != null) {
            packageId = p.getCargoPackage().getId();
            barcode = p.getCargoPackage().getTrackingBarcode();
            if (p.getCargoPackage().getPackageType() != null) {
                packageName = p.getCargoPackage().getPackageType().getName();
            }
        }
        if (packageName == null) {
            packageName = barcode != null ? barcode : "Kiện hàng #" + p.getId();
        }

        return PlacementStepResponse.builder()
                .placementId(p.getId())
                .packageId(packageId)
                .packageName(packageName)
                .trackingBarcode(barcode)
                .posX(p.getPosX())
                .posY(p.getPosY())
                .posZ(p.getPosZ())
                .packedLength(p.getPackedLength())
                .packedWidth(p.getPackedWidth())
                .packedHeight(p.getPackedHeight())
                .rotation(p.getRotationType())
                .sequence(p.getStepSequence())
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
