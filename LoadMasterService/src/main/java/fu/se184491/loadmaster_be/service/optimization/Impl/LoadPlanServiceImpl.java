package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationAlgorithm;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import fu.se184491.loadmaster_be.dto.request.optimization.PinPlacementRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.PinnedPlacementDto;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunOptimizationRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementResultDto;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanComparisonResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlanMetricsDto;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.optimization.UnplacedPackageRepository;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanService;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import fu.se184491.loadmaster_be.service.credit.Impl.CreditServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoadPlanServiceImpl implements LoadPlanService {

    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final AuditLogRepository auditLogRepository;
    private final OptimizationJobRepository optimizationJobRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final UnplacedPackageRepository unplacedPackageRepository;
    private final OptimizationEngineClient optimizationEngineClient;
    private final fu.se184491.loadmaster_be.service.credit.CreditService creditService;
    private final fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository deliveryStopRepository;
    private final CurrentUserService currentUserService;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final PlatformTransactionManager transactionManager;

    /** Hạng của gói đang hoạt động (ACTIVE) của công ty sở hữu chuyến; gói hết hạn, đã huỷ hay chưa có gói thì BASIC. */
    private SubscriptionTier currentTier(Trip trip) {
        Long companyId = trip != null && trip.getCompany() != null
                ? trip.getCompany().getId()
                : null;
        if (companyId == null) {
            return SubscriptionTier.BASIC;
        }
        return companySubscriptionRepository.findByCompanyId(companyId)
                .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
                .filter(sub -> sub.getPlan() != null && sub.getPlan().getTier() != null)
                .map(sub -> sub.getPlan().getTier())
                .orElse(SubscriptionTier.BASIC);
    }

    /** Thuật toán của hạng gói hiện tại: BASIC → EP_DBLF, PRO → EP_DBLF_GA, ULTIMATE → EP_DBLF_GA_AI. */
    private OptimizationAlgorithm rerunAlgorithm(LoadPlan currentPlan) {
        return switch (currentTier(currentPlan.getJob() != null ? currentPlan.getJob().getTrip() : null)) {
            case PRO -> OptimizationAlgorithm.EP_DBLF_GA;
            case ULTIMATE -> OptimizationAlgorithm.EP_DBLF_GA_AI;
            default -> OptimizationAlgorithm.EP_DBLF;
        };
    }

    /**
     * Phương án chỉ được ghim, bỏ ghim hay chạy lại khi nó thuộc công ty của người gọi và chuyến của
     * nó còn Đã lập kế hoạch: từ lúc kho bắt đầu xếp, phương án đã chốt không đổi được nữa.
     */
    private LoadPlan editablePlan(Long loadPlanId) {
        LoadPlan loadPlan = loadPlanRepository.findById(loadPlanId)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        Trip trip = loadPlan.getJob() != null ? loadPlan.getJob().getTrip() : null;
        Long companyId = currentUserService.getCurrentCompanyId();
        if (trip == null || trip.getCompany() == null || !Objects.equals(trip.getCompany().getId(), companyId)) {
            throw new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND);
        }
        if (trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_LOCKED);
        }
        return loadPlan;
    }

    @Override
    @Transactional
    public PackagePlacementResponse pinPlacement(Long loadPlanId, PinPlacementRequest request) {
        LoadPlan loadPlan = editablePlan(loadPlanId);

        PackagePlacement placement = packagePlacementRepository.findById(request.getPlacementId())
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));

        validatePlacementBelongsToPlan(placement, loadPlanId);

        boolean oldPinned = Boolean.TRUE.equals(placement.getPinned());
        placement.setPinned(true);
        PackagePlacement savedPlacement = packagePlacementRepository.save(placement);

        saveAuditLog("PIN_PLACEMENT", loadPlan, savedPlacement.getId(), oldPinned, true);
        log.info("Pinned placement {} in load plan {}", savedPlacement.getId(), loadPlan.getId());

        return mapToResponse(savedPlacement);
    }

    @Override
    @Transactional
    public PackagePlacementResponse unpinPlacement(Long loadPlanId, Long placementId) {
        LoadPlan loadPlan = editablePlan(loadPlanId);

        PackagePlacement placement = packagePlacementRepository.findById(placementId)
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));

        validatePlacementBelongsToPlan(placement, loadPlanId);

        boolean oldPinned = Boolean.TRUE.equals(placement.getPinned());
        placement.setPinned(false);
        PackagePlacement savedPlacement = packagePlacementRepository.save(placement);

        saveAuditLog("UNPIN_PLACEMENT", loadPlan, savedPlacement.getId(), oldPinned, false);
        log.info("Unpinned placement {} in load plan {}", savedPlacement.getId(), loadPlan.getId());

        return mapToResponse(savedPlacement);
    }

    /**
     * Ba bước, không giữ giao dịch DB trong lúc chờ dịch vụ tối ưu: (1) tạo job và trừ credit trong một giao dịch
     * ngắn, (2) gọi dịch vụ ngoài giao dịch, (3) lưu kết quả trong giao dịch mới; hỏng ở (2) hay (3) thì đánh dấu job
     * FAILED và hoàn credit, mỗi việc một giao dịch riêng nên không bị cuộn lại cùng lỗi.
     */
    @Override
    public LoadPlanResponse rerunOptimization(Long loadPlanId, RerunOptimizationRequest request) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        RerunStart start = tx.execute(status -> startRerun(loadPlanId, request));

        OptimizationResultDto engineResult;
        try {
            engineResult = optimizationEngineClient.rerunOptimization(start.engineRequest());
        } catch (RuntimeException e) {
            log.error("Optimization engine failed for job {}: {}", start.job().getJobUuid(), e.getMessage());
            failRerun(tx, start);
            throw e;
        }

        try {
            return tx.execute(status -> finishRerun(start, engineResult));
        } catch (RuntimeException e) {
            log.error("Saving rerun result failed for job {}: {}", start.job().getJobUuid(), e.getMessage());
            failRerun(tx, start);
            throw e;
        }
    }

    /** Job mới, yêu cầu gửi dịch vụ tối ưu và những gì bước lưu kết quả cần. */
    private record RerunStart(LoadPlan currentPlan, OptimizationJob job, int pinnedCount, int nextVersion,
                              Long companyId, RerunEngineRequest engineRequest) {
    }

    private void failRerun(TransactionTemplate tx, RerunStart start) {
        try {
            tx.executeWithoutResult(status -> {
                start.job().setStatus(OptimizationJobStatus.FAILED);
                optimizationJobRepository.save(start.job());
            });
        } catch (RuntimeException ex) {
            log.error("Failed to mark rerun job {} as FAILED: {}", start.job().getJobUuid(), ex.getMessage());
        }
        if (start.companyId() == null) {
            return;
        }
        try {
            // Giao dịch riêng của refundCredit: hoàn credit được giữ lại dù lỗi ở trên.
            creditService.refundCredit(start.companyId(), start.job().getJobUuid());
        } catch (RuntimeException refundEx) {
            log.error("Failed to refund credit for rerun job {}: {}", start.job().getJobUuid(), refundEx.getMessage());
        }
    }

    private RerunStart startRerun(Long loadPlanId, RerunOptimizationRequest request) {
        LoadPlan currentPlan = editablePlan(loadPlanId);

        // 1. Load pinned placements from current plan
        List<PackagePlacement> pinnedPlacements = packagePlacementRepository.findByLoadPlanIdAndPinnedTrue(loadPlanId);

        // 2. Create new OptimizationJob with parentPlanId
        int nextVersion = (currentPlan.getVersion() != null ? currentPlan.getVersion() : 1) + 1;
        String jobUuid = UUID.randomUUID().toString();
        OptimizationObjective objective = request.getObjective() != null
                ? request.getObjective()
                : (currentPlan.getJob() != null && currentPlan.getJob().getObjective() != null
                    ? currentPlan.getJob().getObjective()
                    : OptimizationObjective.MAX_VOLUME);
        Integer timeLimit = request.getTimeLimitSec() != null ? request.getTimeLimitSec() : 60;

        OptimizationJob newJob = OptimizationJob.builder()
                .trip(currentPlan.getJob() != null ? currentPlan.getJob().getTrip() : null)
                .jobUuid(jobUuid)
                // Chạy lại theo hạng gói của công ty lúc này, như lần chạy đầy đủ
                .algorithmName(rerunAlgorithm(currentPlan))
                .objective(objective)
                .timeLimitSec(timeLimit)
                .seed(request.getSeed())
                .parentPlanId(currentPlan.getId())
                .status(OptimizationJobStatus.RUNNING)
                .build();
        newJob = optimizationJobRepository.save(newJob);

        // 3. Prepare pinned list for Engine
        List<PinnedPlacementDto> pinnedDtos = pinnedPlacements.stream()
                .map(p -> PinnedPlacementDto.builder()
                        .placementId(p.getId())
                        .packageId(p.getCargoPackage() != null ? p.getCargoPackage().getId() : null)
                        .posX(p.getPosX())
                        .posY(p.getPosY())
                        .posZ(p.getPosZ())
                        .packedLength(p.getPackedLength())
                        .packedWidth(p.getPackedWidth())
                        .packedHeight(p.getPackedHeight())
                        .rotationType(p.getRotationType())
                        .stepSequence(p.getStepSequence())
                        .build())
                .collect(Collectors.toList());

        RerunEngineRequest engineRequest = RerunEngineRequest.builder()
                .jobUuid(jobUuid)
                .tripId(newJob.getTrip() != null ? newJob.getTrip().getId() : null)
                .parentPlanId(currentPlan.getId())
                .objective(objective.name())
                .timeLimitSec(timeLimit)
                .seed(request.getSeed())
                .pinnedPlacements(pinnedDtos)
                .subscriptionTier(currentTier(newJob.getTrip()).name())
                .algorithmName(newJob.getAlgorithmName().name())
                .build();

        // S8-08 Credit check & deduction before sending to Engine (Rerun = 1 credit)
        Long companyId = newJob.getTrip() != null && newJob.getTrip().getCompany() != null
                ? newJob.getTrip().getCompany().getId()
                : null;
        if (companyId != null) {
            creditService.deductCredit(companyId, jobUuid, CreditServiceImpl.RERUN_OPTIMIZATION_CREDIT_COST);
        }
        return new RerunStart(currentPlan, newJob, pinnedPlacements.size(), nextVersion, companyId, engineRequest);
    }

    private LoadPlanResponse finishRerun(RerunStart start, OptimizationResultDto engineResult) {
        LoadPlan currentPlan = start.currentPlan();
        OptimizationJob newJob = start.job();
        int nextVersion = start.nextVersion();

        // Update Job status
        newJob.setStatus(OptimizationJobStatus.COMPLETED);
        if (engineResult != null && engineResult.getComputationMs() != null) {
            newJob.setComputationMs(engineResult.getComputationMs());
        }
        optimizationJobRepository.save(newJob);

        // 5. Save new plan with version = old.version + 1 and parentPlanId
        String baseName = currentPlan.getPlanName() != null
                ? currentPlan.getPlanName().replaceAll(" \\(v\\d+\\)$", "")
                : "Plan";
        String newPlanName = baseName + " (v" + nextVersion + ")";

        LoadPlan newPlan = LoadPlan.builder()
                .job(newJob)
                .planName(newPlanName)
                .version(nextVersion)
                .parentPlanId(currentPlan.getId())
                .packedItemsCount(engineResult != null ? engineResult.getPackedItemsCount() : 0)
                .volumeUtilization(engineResult != null ? engineResult.getVolumeUtilization() : BigDecimal.ZERO)
                .weightUtilization(engineResult != null ? engineResult.getWeightUtilization() : BigDecimal.ZERO)
                .algorithmName(engineResult != null ? engineResult.getAlgorithmName() : null)
                .approved(false)
                .build();
        newPlan = loadPlanRepository.save(newPlan);

        // Save placements
        List<PackagePlacement> savedPlacements = new ArrayList<>();
        if (engineResult != null && engineResult.getPlacements() != null) {
            for (PlacementResultDto pDto : engineResult.getPlacements()) {
                CargoPackage pkg = null;
                if (pDto.getPackageId() != null) {
                    pkg = cargoPackageRepository.findById(pDto.getPackageId()).orElse(null);
                }
                fu.se184491.loadmaster_be.entity.trip.DeliveryStop stopZone = pDto.getStopZoneId() != null
                        ? deliveryStopRepository.findById(pDto.getStopZoneId()).orElse(null)
                        : null;
                PackagePlacement placement = PackagePlacement.builder()
                        .loadPlan(newPlan)
                        .cargoPackage(pkg)
                        .stopZone(stopZone)
                        .posX(pDto.getPosX())
                        .posY(pDto.getPosY())
                        .posZ(pDto.getPosZ())
                        .packedLength(pDto.getPackedLength())
                        .packedWidth(pDto.getPackedWidth())
                        .packedHeight(pDto.getPackedHeight())
                        .rotationType(pDto.getRotationType())
                        .stepSequence(pDto.getStepSequence())
                        .pinned(Boolean.TRUE.equals(pDto.getPinned()))
                        .build();
                savedPlacements.add(packagePlacementRepository.save(placement));
            }
        }

        // AuditLog for Rerun
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("parentPlanId", currentPlan.getId());
        oldValues.put("parentVersion", currentPlan.getVersion());

        Map<String, Object> newValues = new HashMap<>();
        newValues.put("newPlanId", newPlan.getId());
        newValues.put("version", nextVersion);
        newValues.put("pinnedCount", start.pinnedCount());

        AuditLog auditLog = AuditLog.builder()
                .actionType("RERUN_OPTIMIZATION")
                .companyId(AuditCompany.ofJob(newJob))
                .entityName("LoadPlan")
                .entityId(String.valueOf(newPlan.getId()))
                .oldValues(oldValues)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);

        log.info("Rerun optimization completed for plan {}, created new plan {} version {}",
                currentPlan.getId(), newPlan.getId(), nextVersion);

        return LoadPlanResponse.builder()
                .id(newPlan.getId())
                .jobId(newJob.getId())
                .jobUuid(newJob.getJobUuid())
                .planName(newPlan.getPlanName())
                .version(newPlan.getVersion())
                .parentPlanId(newPlan.getParentPlanId())
                .packedItemsCount(newPlan.getPackedItemsCount())
                .volumeUtilization(newPlan.getVolumeUtilization())
                .weightUtilization(newPlan.getWeightUtilization())
                .approved(newPlan.getApproved())
                .cogX(newPlan.getCogX())
                .cogY(newPlan.getCogY())
                .cogZ(newPlan.getCogZ())
                .frontAxleLoad(newPlan.getFrontAxleLoad())
                .rearAxleLoad(newPlan.getRearAxleLoad())
                .rehandlingCount(newPlan.getRehandlingCount())
                .algorithmName(newPlan.getAlgorithmName() != null ? newPlan.getAlgorithmName() : jobAlgorithm(newJob))
                .runAlgorithm(jobAlgorithm(newJob))
                .placements(savedPlacements.stream().map(this::mapToResponse).collect(Collectors.toList()))
                .build();
    }

    private static String jobAlgorithm(OptimizationJob job) {
        return job != null && job.getAlgorithmName() != null ? job.getAlgorithmName().name() : null;
    }

    private void validatePlacementBelongsToPlan(PackagePlacement placement, Long loadPlanId) {
        if (placement.getLoadPlan() == null || !loadPlanId.equals(placement.getLoadPlan().getId())) {
            throw new AppException(ErrorCode.PLACEMENT_NOT_IN_PLAN);
        }
    }

    private void saveAuditLog(String actionType, LoadPlan loadPlan, Long placementId, boolean oldPinned, boolean newPinned) {
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("pinned", oldPinned);

        Map<String, Object> newValues = new HashMap<>();
        newValues.put("pinned", newPinned);

        AuditLog auditLog = AuditLog.builder()
                .actionType(actionType)
                .companyId(AuditCompany.ofPlan(loadPlan))
                .entityName("PackagePlacement")
                .entityId(String.valueOf(placementId))
                .oldValues(oldValues)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditLog);
    }

    @Override
    @Transactional(readOnly = true)
    public PlanComparisonResponse comparePlans(Long planId1, Long planId2) {
        LoadPlan plan1 = loadPlanRepository.findById(planId1)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));

        LoadPlan plan2 = loadPlanRepository.findById(planId2)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));

        PlanMetricsDto m1 = buildPlanMetrics(plan1);
        PlanMetricsDto m2 = buildPlanMetrics(plan2);

        log.info("Compared load plan {} and load plan {}", planId1, planId2);

        return PlanComparisonResponse.builder()
                .plan1Metrics(m1)
                .plan2Metrics(m2)
                .build();
    }

    private PlanMetricsDto buildPlanMetrics(LoadPlan plan) {
        long unplaced = unplacedPackageRepository.countByLoadPlanId(plan.getId());
        Long computeMs = (plan.getJob() != null && plan.getJob().getComputationMs() != null)
                ? plan.getJob().getComputationMs()
                : null;

        return PlanMetricsDto.builder()
                .planId(plan.getId())
                .planName(plan.getPlanName())
                .version(plan.getVersion())
                .volumeUtil(plan.getVolumeUtilization())
                .weightUtil(plan.getWeightUtilization())
                .packedCount(plan.getPackedItemsCount())
                .unplacedCount(unplaced)
                .computeMs(computeMs)
                .cogX(plan.getCogX())
                .cogY(plan.getCogY())
                .cogZ(plan.getCogZ())
                .frontAxleLoad(plan.getFrontAxleLoad())
                .rearAxleLoad(plan.getRearAxleLoad())
                .rehandlingCount(plan.getRehandlingCount())
                .build();
    }

    private PackagePlacementResponse mapToResponse(PackagePlacement entity) {
        if (entity == null) {
            return null;
        }
        return PackagePlacementResponse.builder()
                .id(entity.getId())
                .loadPlanId(entity.getLoadPlan() != null ? entity.getLoadPlan().getId() : null)
                .packageId(entity.getCargoPackage() != null ? entity.getCargoPackage().getId() : null)
                .trackingBarcode(entity.getCargoPackage() != null ? entity.getCargoPackage().getTrackingBarcode() : null)
                .stopZoneId(entity.getStopZone() != null ? entity.getStopZone().getId() : null)
                .stopZoneName(entity.getStopZone() != null ? entity.getStopZone().getStopName() : null)
                .posX(entity.getPosX())
                .posY(entity.getPosY())
                .posZ(entity.getPosZ())
                .packedLength(entity.getPackedLength())
                .packedWidth(entity.getPackedWidth())
                .packedHeight(entity.getPackedHeight())
                .rotationType(entity.getRotationType())
                .stepSequence(entity.getStepSequence())
                .pinned(entity.getPinned())
                .build();
    }
}
