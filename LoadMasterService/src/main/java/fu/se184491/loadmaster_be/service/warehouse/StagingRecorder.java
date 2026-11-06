package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.warehouse.ShortageStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.StagingShortageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghi một kiện vào khu chờ — dùng chung cho đối chiếu bằng nhãn của kho và cho lúc điều phối viên duyệt xác nhận tay bước soạn. Xác nhận tay
 * lúc soạn chưa tính là đã soạn cho tới khi được duyệt.
 */
@Component
@RequiredArgsConstructor
public class StagingRecorder {

    private final StagingConfirmationRepository stagingConfirmationRepository;
    private final StagingShortageRepository stagingShortageRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final WarehouseSessionLookup lookup;

    /**
     * Ghi kiện vào khu chờ (không ghi lần hai) và đóng báo thiếu còn hiệu lực của kiện. {@code stagedBy} là người soạn (kho), {@code actor} là
     * người ghi nhật ký (kho, hoặc điều phối viên khi duyệt xác nhận tay).
     */
    public void record(Trip trip, CargoPackage pkg, StagingMethod method, User stagedBy, User actor) {
        if (stagingConfirmationRepository.findByTripIdAndCargoPackageId(trip.getId(), pkg.getId()).isPresent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        StagingConfirmation confirmation = stagingConfirmationRepository.save(StagingConfirmation.builder()
                .trip(trip)
                .cargoPackage(pkg)
                .method(method)
                .stagedBy(stagedBy)
                .stagedAt(now)
                .build());
        for (ShortageStatus status : List.of(ShortageStatus.OPEN, ShortageStatus.KEEP_SEARCHING)) {
            stagingShortageRepository.findFirstByTripIdAndCargoPackageIdAndStatus(trip.getId(), pkg.getId(), status)
                    .ifPresent(shortage -> {
                        shortage.setStatus(ShortageStatus.FOUND);
                        shortage.setResolvedBy(actor);
                        shortage.setResolvedAt(now);
                        stagingShortageRepository.save(shortage);
                    });
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("tripId", trip.getId());
        values.put("method", method.name());
        lookup.audit(trip, actor, "WAREHOUSE_STAGED", "StagingConfirmation", confirmation.getId(), values);
    }

    /** Bỏ xác nhận tay bước soạn còn chờ của kiện (kiện vừa được đối chiếu bằng nhãn, hoặc kho gửi lại xác nhận tay mới). */
    public void dropPendingManual(Long tripId, Long packageId) {
        for (ManualUnloadConfirmation entry : manualConfirmationRepository.findByTripIdAndStatusOrderByRequestedAtAsc(tripId,
                ManualConfirmStatus.PENDING)) {
            if (entry.getStep() == ManualConfirmStep.STAGING && entry.getCargoPackage() != null
                    && packageId.equals(entry.getCargoPackage().getId())) {
                manualConfirmationRepository.delete(entry);
            }
        }
    }
}
