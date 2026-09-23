package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.constant.optimization.UnplacedReason;
import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.optimization.UnplacedPackageRepository;
import fu.se184491.loadmaster_be.service.optimize.JobNotificationService;
import fu.se184491.loadmaster_be.service.optimize.OptimizationResultPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional persistence of a single optimization result (S3-09).
 *
 * <p>All DB writes run in ONE transaction:
 * <pre>
 *   INSERT load_plans
 *   INSERT package_placements (batch)
 *   INSERT unplaced_packages  (batch)
 *   UPDATE optimization_jobs  (status = COMPLETED, computation_ms)
 * </pre>
 *
 * <p>If any step fails the entire transaction is rolled back, keeping
 * the DB consistent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizationResultPersistenceServiceImpl implements OptimizationResultPersistenceService {

    private final OptimizationJobRepository   jobRepository;
    private final LoadPlanRepository          loadPlanRepository;
    private final PackagePlacementRepository  placementRepository;
    private final UnplacedPackageRepository   unplacedRepository;
    private final JobNotificationService      jobNotificationService;

    // ── persist ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void persist(String jobUuid, OptimizationResult result) {
        // 1. Load job
        OptimizationJob job = jobRepository.findByJobUuid(jobUuid)
                .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));

        // 2. INSERT LoadPlan
        LoadPlan plan = buildLoadPlan(job, result);
        plan = loadPlanRepository.save(plan);
        log.info("[job={}] LoadPlan saved (id={}, packed={})",
                jobUuid, plan.getId(), plan.getPackedItemsCount());

        // 3. INSERT PackagePlacements
        List<PackagePlacement> placements = buildPlacements(plan, result);
        if (!placements.isEmpty()) {
            placementRepository.saveAll(placements);
            log.info("[job={}] {} PackagePlacement(s) saved", jobUuid, placements.size());
        }

        // 4. INSERT UnplacedPackages
        List<UnplacedPackage> unplaced = buildUnplaced(plan, result);
        if (!unplaced.isEmpty()) {
            unplacedRepository.saveAll(unplaced);
            log.info("[job={}] {} UnplacedPackage(s) saved", jobUuid, unplaced.size());
        }

        // 5. UPDATE job status
        long computationMs = result.getMetrics() != null && result.getMetrics().getComputationMs() != null
                ? result.getMetrics().getComputationMs() : 0L;
        job.setComputationMs(computationMs);
        job.setStatus(OptimizationJobStatus.COMPLETED);
        jobRepository.save(job);
        log.info("[job={}] Status updated to COMPLETED, computationMs={}", jobUuid, computationMs);

        // 6. Notify WebSocket subscribers (S3-10)
        int packedCount = result.getMetrics() != null && result.getMetrics().getPackedCount() != null
                ? result.getMetrics().getPackedCount() : 0;
        boolean hasUnplaced = result.getUnplaced() != null && !result.getUnplaced().isEmpty();
        OptimizationJobStatus notifyStatus = hasUnplaced
                ? OptimizationJobStatus.PARTIAL : OptimizationJobStatus.COMPLETED;
        String msg = hasUnplaced
                ? String.format("Partial solution: %d package(s) placed, %d unplaced",
                        packedCount, result.getUnplaced().size())
                : String.format("Optimization completed: %d package(s) placed", packedCount);
        jobNotificationService.notifyJobStatus(jobUuid, notifyStatus, plan.getId(), msg);
    }

    // ── Private builders ──────────────────────────────────────────────────────

    private LoadPlan buildLoadPlan(OptimizationJob job, OptimizationResult result) {
        OptimizationResult.MetricsData metrics = result.getMetrics();
        int packedCount = (metrics != null && metrics.getPackedCount() != null)
                ? metrics.getPackedCount() : 0;
        BigDecimal volUtil = (metrics != null) ? metrics.getVolumeUtilization() : null;
        BigDecimal wgtUtil = (metrics != null) ? metrics.getWeightUtilization() : null;

        return LoadPlan.builder()
                .job(job)
                .planName("Plan-" + job.getJobUuid())
                .packedItemsCount(packedCount)
                .volumeUtilization(volUtil)
                .weightUtilization(wgtUtil)
                .approved(false)
                .build();
    }

    private List<PackagePlacement> buildPlacements(LoadPlan plan, OptimizationResult result) {
        List<OptimizationResult.PlacementData> placementData = result.getPlacements();
        if (placementData == null || placementData.isEmpty()) return List.of();

        List<PackagePlacement> entities = new ArrayList<>(placementData.size());
        for (OptimizationResult.PlacementData pd : placementData) {
            CargoPackage pkg = CargoPackage.builder().id(pd.getPackageId()).build();
            entities.add(PackagePlacement.builder()
                    .loadPlan(plan)
                    .cargoPackage(pkg)
                    .posX(pd.getPosX())
                    .posY(pd.getPosY())
                    .posZ(pd.getPosZ())
                    .packedLength(pd.getPackedLength())
                    .packedWidth(pd.getPackedWidth())
                    .packedHeight(pd.getPackedHeight())
                    .rotationType(pd.getRotationType())
                    .stepSequence(pd.getStepSequence())
                    .pinned(false)
                    .build());
        }
        return entities;
    }

    private List<UnplacedPackage> buildUnplaced(LoadPlan plan, OptimizationResult result) {
        List<OptimizationResult.UnplacedData> unplacedData = result.getUnplaced();
        if (unplacedData == null || unplacedData.isEmpty()) return List.of();

        List<UnplacedPackage> entities = new ArrayList<>(unplacedData.size());
        for (OptimizationResult.UnplacedData ud : unplacedData) {
            CargoPackage pkg = CargoPackage.builder().id(ud.getPackageId()).build();
            UnplacedReason reason = parseReason(ud.getReason());
            entities.add(UnplacedPackage.builder()
                    .loadPlan(plan)
                    .cargoPackage(pkg)
                    .rejectionReason(reason)
                    .build());
        }
        return entities;
    }

    /**
     * Parses the raw reason string from the engine to the {@link UnplacedReason} enum.
     * Falls back to {@link UnplacedReason#OUT_OF_SPACE} for unknown values.
     */
    public static UnplacedReason parseReason(String raw) {
        if (raw == null) return UnplacedReason.OUT_OF_SPACE;
        try {
            return UnplacedReason.valueOf(raw.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return UnplacedReason.OUT_OF_SPACE;
        }
    }
}
