package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.warehouse.LoadingDamage;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingShortage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiến độ kho của một chuyến ngoài các lần xác nhận vị trí: kiện đã soạn, báo thiếu còn hiệu lực, kiện hỏng bị bỏ lại kho và xác nhận tay
 * của kho (soạn, xếp). Đọc một lần cho một phiên, dùng chung cho phiên trả về và cho các luật chặn.
 */
@Component
@RequiredArgsConstructor
public class WarehouseProgress {

    private final StagingConfirmationRepository stagingConfirmationRepository;
    private final StagingShortageRepository stagingShortageRepository;
    private final LoadingDamageRepository loadingDamageRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;

    /**
     * @param stagedByPackage   lần soạn của từng kiện
     * @param shortageByPackage báo thiếu còn hiệu lực ({@code OPEN} hoặc {@code KEEP_SEARCHING}) của từng kiện
     * @param leftOut           vị trí của kiện hỏng bị bỏ lại kho
     * @param manualByPackage   xác nhận tay mới nhất của kho (soạn, xếp) với từng kiện
     */
    public record State(Map<Long, StagingConfirmation> stagedByPackage,
                        Map<Long, StagingShortage> shortageByPackage,
                        Set<Long> leftOut,
                        Map<Long, ManualUnloadConfirmation> manualByPackage) {

        public static State empty() {
            return new State(Map.of(), Map.of(), Set.of(), Map.of());
        }

        public int openShortages() {
            return (int) shortageByPackage.values().stream().filter(entry -> entry.getStatus() == ShortageStatus.OPEN).count();
        }

        public int pendingManual() {
            return (int) manualByPackage.values().stream().filter(entry -> entry.getStatus() == ManualConfirmStatus.PENDING).count();
        }

        public boolean staged(PackagePlacement placement) {
            return placement.getCargoPackage() != null && stagedByPackage.containsKey(placement.getCargoPackage().getId());
        }

        public int stagedCount(List<PackagePlacement> placements) {
            return (int) placements.stream().filter(this::staged).count();
        }

        /** Soạn đủ: mọi kiện của phương án đã vào khu chờ và không còn báo thiếu chờ quyết. */
        public boolean stagingComplete(List<PackagePlacement> placements) {
            return openShortages() == 0 && placements.stream()
                    .filter(placement -> placement.getCargoPackage() != null)
                    .allMatch(this::staged);
        }
    }

    public State of(Long tripId, Long loadPlanId) {
        Map<Long, StagingConfirmation> staged = new HashMap<>();
        for (StagingConfirmation entry : stagingConfirmationRepository.findByTripId(tripId)) {
            if (entry.getCargoPackage() != null) {
                staged.put(entry.getCargoPackage().getId(), entry);
            }
        }
        Map<Long, StagingShortage> shortages = new HashMap<>();
        for (ShortageStatus status : List.of(ShortageStatus.KEEP_SEARCHING, ShortageStatus.OPEN)) {
            for (StagingShortage entry : stagingShortageRepository.findByTripIdAndStatusOrderByReportedAtAsc(tripId, status)) {
                if (entry.getCargoPackage() != null) {
                    shortages.put(entry.getCargoPackage().getId(), entry);
                }
            }
        }
        Set<Long> leftOut = new HashSet<>();
        if (loadPlanId != null) {
            for (LoadingDamage damage : loadingDamageRepository.findByPlacementLoadPlanId(loadPlanId)) {
                if (damage.getOutcome() == DamageOutcome.LEFT_OUT && damage.getPlacement() != null) {
                    leftOut.add(damage.getPlacement().getId());
                }
            }
        }
        Map<Long, ManualUnloadConfirmation> manual = new HashMap<>();
        for (ManualUnloadConfirmation entry : manualConfirmationRepository.findByTripIdOrderByRequestedAtAsc(tripId)) {
            if (!entry.isUnloadingStep() && entry.getCargoPackage() != null && ofPlan(entry, loadPlanId)) {
                manual.put(entry.getCargoPackage().getId(), entry);
            }
        }
        return new State(staged, shortages, leftOut, manual);
    }

    /**
     * Xác nhận tay bước xếp thuộc phiên của phương án đang xếp: phiên cũ đã dừng (chuyến quay về lập kế hoạch rồi duyệt phương án mới) để
     * lại xác nhận tay đã quyết của chỗ xếp cũ, không được lẫn vào phiên mới.
     */
    private static boolean ofPlan(ManualUnloadConfirmation entry, Long loadPlanId) {
        if (loadPlanId == null || entry.getStep() != ManualConfirmStep.LOADING || entry.getPlacement() == null
                || entry.getPlacement().getLoadPlan() == null) {
            return true;
        }
        return loadPlanId.equals(entry.getPlacement().getLoadPlan().getId());
    }

    /** Vị trí của kiện hỏng đã bị bỏ lại kho: tính là đã xử lý, kiện không lên xe. */
    public boolean isLeftOut(Long placementId) {
        return loadingDamageRepository.existsByPlacementId(placementId);
    }

    /** Phiên có bước soạn hàng mà chưa soạn đủ: chưa xếp được kiện nào. */
    public void requireStagingComplete(LoadingExecution execution, Long tripId, List<PackagePlacement> placements) {
        if (!Boolean.TRUE.equals(execution.getStagingRequired())) {
            return;
        }
        Long planId = execution.getLoadPlan() != null ? execution.getLoadPlan().getId() : null;
        if (!of(tripId, planId).stagingComplete(placements)) {
            throw new AppException(ErrorCode.STAGING_INCOMPLETE);
        }
    }
}
