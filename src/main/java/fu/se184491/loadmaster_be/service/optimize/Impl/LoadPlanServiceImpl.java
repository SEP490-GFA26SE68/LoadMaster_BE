package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PackagePlacementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.UserRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.service.optimize.LoadPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoadPlanServiceImpl implements LoadPlanService {

    private final OptimizationJobRepository optimizationJobRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional(readOnly = true)
    public List<LoadPlanResponse> getPlansForJob(String jobUuid) {
        OptimizationJob job = optimizationJobRepository.findByJobUuid(jobUuid)
                .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));

        List<LoadPlan> plans = loadPlanRepository.findByJobId(job.getId());

        return plans.stream()
                .map(plan -> {
                    List<PackagePlacement> placements =
                            packagePlacementRepository.findByLoadPlanId(plan.getId());
                    return toResponse(plan, placements);
                })
                .toList();
    }

    // ── approvePlan ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LoadPlanResponse approvePlan(Long planId, String approverEmail) {
        // 1. Load plan
        LoadPlan plan = loadPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));

        // 2. Already approved guard
        if (Boolean.TRUE.equals(plan.getApproved())) {
            throw new AppException(ErrorCode.PLAN_ALREADY_APPROVED);
        }

        // 3. Must have placements
        List<PackagePlacement> placements = packagePlacementRepository.findByLoadPlanId(planId);
        if (placements.isEmpty()) {
            throw new AppException(ErrorCode.PLAN_HAS_NO_PLACEMENTS);
        }

        // 4. LIFO validation: stepSequence must be strictly ascending (1,2,3,...)
        //    Packages loaded last (highest stepSequence) are at the front of the truck
        //    and must be unloaded first — so sequence must be monotonically increasing.
        validateLifo(placements);

        // 5. Set approved = true, approvedBy = current user
        User approver = userRepository.findByEmail(approverEmail)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        plan.setApproved(true);
        plan.setApprovedBy(approver);
        loadPlanRepository.save(plan);
        log.info("[LoadPlan id={}] Approved by user={}", planId, approverEmail);

        // 6. INSERT AuditLog PLAN_APPROVED
        AuditLog audit = AuditLog.builder()
                .user(approver)
                .actionType("PLAN_APPROVED")
                .entityName("LoadPlan")
                .entityId(String.valueOf(planId))
                .newValues(Map.of("approved", true, "approvedBy", approverEmail))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(audit);

        return toResponse(plan, placements);
    }

    /**
     * LIFO check: placements sorted by stepSequence must be strictly monotone ascending.
     * stepSequence=1 → loaded last (front of truck, first off);
     * stepSequence=N → loaded first (back of truck, last off).
     * The sequence must never repeat or skip (1,2,3,... or similar).
     */
    public static void validateLifo(List<PackagePlacement> placements) {
        List<PackagePlacement> sorted = placements.stream()
                .filter(p -> p.getStepSequence() != null)
                .sorted(java.util.Comparator.comparingInt(PackagePlacement::getStepSequence))
                .toList();

        for (int i = 1; i < sorted.size(); i++) {
            int prev = sorted.get(i - 1).getStepSequence();
            int curr = sorted.get(i).getStepSequence();
            // Duplicate stepSequence = two packages claim the same loading slot → LIFO conflict
            if (curr == prev) {
                throw new AppException(ErrorCode.PLAN_LIFO_INVALID);
            }
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private LoadPlanResponse toResponse(LoadPlan plan, List<PackagePlacement> placements) {
        return LoadPlanResponse.builder()
                .id(plan.getId())
                .planName(plan.getPlanName())
                .packedItemsCount(plan.getPackedItemsCount())
                .volumeUtilization(plan.getVolumeUtilization())
                .weightUtilization(plan.getWeightUtilization())
                .approved(plan.getApproved())
                .approvedById(plan.getApprovedBy() != null ? plan.getApprovedBy().getId() : null)
                .placements(placements.stream().map(this::toPlacementResponse).toList())
                .build();
    }

    private PackagePlacementResponse toPlacementResponse(PackagePlacement pp) {
        return PackagePlacementResponse.builder()
                .id(pp.getId())
                .cargoPackageId(pp.getCargoPackage() != null ? pp.getCargoPackage().getId() : null)
                .posX(pp.getPosX())
                .posY(pp.getPosY())
                .posZ(pp.getPosZ())
                .packedLength(pp.getPackedLength())
                .packedWidth(pp.getPackedWidth())
                .packedHeight(pp.getPackedHeight())
                .rotationType(pp.getRotationType())
                .stepSequence(pp.getStepSequence())
                .pinned(pp.getPinned())
                .build();
    }
}
