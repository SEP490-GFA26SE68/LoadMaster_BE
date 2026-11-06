package fu.se184491.loadmaster_be.service.optimization.Impl;

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
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.optimization.UnplacedPackageRepository;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanService;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CurrentUserService currentUserService;
    private final fu.se184491.loadmaster_be.service.optimization.OptimizationEntitlementService entitlements;
    private final PackagePlacementRepository packagePlacementRepository;
    private final AuditLogRepository auditLogRepository;
    private final OptimizationJobRepository optimizationJobRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final UnplacedPackageRepository unplacedPackageRepository;
    private final OptimizationEngineClient optimizationEngineClient;
    private final fu.se184491.loadmaster_be.service.credit.CreditService creditService;

    @Override
    @Transactional
    public PackagePlacementResponse pinPlacement(Long loadPlanId, PinPlacementRequest request) {
        LoadPlan loadPlan = requireOwnedPlan(loadPlanId);

        PackagePlacement placement = packagePlacementRepository.findById(request.getPlacementId())
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));

        validatePlacementBelongsToPlan(placement, loadPlanId);

        boolean oldPinned = Boolean.TRUE.equals(placement.getPinned());
        placement.setPinned(true);
        PackagePlacement savedPlacement = packagePlacementRepository.save(placement);

        saveAuditLog("PIN_PLACEMENT", savedPlacement.getId(), oldPinned, true);
        log.info("Pinned placement {} in load plan {}", savedPlacement.getId(), loadPlan.getId());

        return mapToResponse(savedPlacement);
    }

    @Override
    @Transactional
    public PackagePlacementResponse unpinPlacement(Long loadPlanId, Long placementId) {
        LoadPlan loadPlan = requireOwnedPlan(loadPlanId);

        PackagePlacement placement = packagePlacementRepository.findById(placementId)
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));

        validatePlacementBelongsToPlan(placement, loadPlanId);

        boolean oldPinned = Boolean.TRUE.equals(placement.getPinned());
        placement.setPinned(false);
        PackagePlacement savedPlacement = packagePlacementRepository.save(placement);

        saveAuditLog("UNPIN_PLACEMENT", savedPlacement.getId(), oldPinned, false);
        log.info("Unpinned placement {} in load plan {}", savedPlacement.getId(), loadPlan.getId());

        return mapToResponse(savedPlacement);
    }

    @Override
    @Transactional
    public LoadPlanResponse rerunOptimization(Long loadPlanId, RerunOptimizationRequest request) {
        LoadPlan currentPlan = requireOwnedPlan(loadPlanId);
        entitlements.requireActive(currentPlan.getJob().getTrip().getCompany().getId());
        throw new AppException(ErrorCode.OPTIMIZATION_RERUN_UNAVAILABLE);
    }

    private void validatePlacementBelongsToPlan(PackagePlacement placement, Long loadPlanId) {
        if (placement.getLoadPlan() == null || !loadPlanId.equals(placement.getLoadPlan().getId())) {
            throw new AppException(ErrorCode.PLACEMENT_NOT_IN_PLAN);
        }
    }

    private void saveAuditLog(String actionType, Long placementId, boolean oldPinned, boolean newPinned) {
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("pinned", oldPinned);

        Map<String, Object> newValues = new HashMap<>();
        newValues.put("pinned", newPinned);

        AuditLog auditLog = AuditLog.builder()
                .actionType(actionType)
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
        LoadPlan plan1 = requireOwnedPlan(planId1);

        LoadPlan plan2 = requireOwnedPlan(planId2);

        PlanMetricsDto m1 = buildPlanMetrics(plan1);
        PlanMetricsDto m2 = buildPlanMetrics(plan2);

        log.info("Compared load plan {} and load plan {}", planId1, planId2);

        return PlanComparisonResponse.builder()
                .plan1Metrics(m1)
                .plan2Metrics(m2)
                .build();
    }

    private LoadPlan requireOwnedPlan(Long planId) {
        LoadPlan plan = loadPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        Long companyId = currentUserService.getCurrentCompanyId();
        if (plan.getJob() == null || plan.getJob().getTrip() == null
                || plan.getJob().getTrip().getCompany() == null || companyId == null
                || !companyId.equals(plan.getJob().getTrip().getCompany().getId())) {
            throw new AccessDeniedException("Load plan does not belong to the current company");
        }
        return plan;
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
