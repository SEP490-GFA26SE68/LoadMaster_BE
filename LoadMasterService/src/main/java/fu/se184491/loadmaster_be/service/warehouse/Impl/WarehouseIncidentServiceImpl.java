package fu.se184491.loadmaster_be.service.warehouse.Impl;

import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmReason;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.warehouse.DamageOutcome;
import fu.se184491.loadmaster_be.dto.request.driver.ManualUnloadRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.RecordSealRequest;
import fu.se184491.loadmaster_be.dto.request.warehouse.ReportDamageRequest;
import fu.se184491.loadmaster_be.dto.response.driver.ManualConfirmationResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.DamageResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.PlacementStepResponse;
import fu.se184491.loadmaster_be.dto.response.warehouse.SealResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.warehouse.LoadingDamage;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.driver.ManualUnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingDamageRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverDeliveryServiceImpl;
import fu.se184491.loadmaster_be.service.warehouse.LoadingReplanService;
import fu.se184491.loadmaster_be.service.warehouse.PlacementSupport;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseIncidentService;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup;
import fu.se184491.loadmaster_be.service.warehouse.WarehouseSessionLookup.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseIncidentServiceImpl implements WarehouseIncidentService {

    /** Độ dài tối đa của số seal (cột {@code seal_number} rộng hơn). */
    static final int MAX_SEAL_LENGTH = 32;

    private final WarehouseSessionLookup lookup;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final LoadingDamageRepository loadingDamageRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final ManualUnloadConfirmationRepository manualConfirmationRepository;
    private final CargoPackageRepository cargoPackageRepository;
    private final LoadingReplanService loadingReplanService;

    @Override
    @Transactional
    public ManualConfirmationResponse confirmPlacementManually(Long placementId, ManualUnloadRequest request) {
        String note = WarehouseSessionLookup.trimmed(request.getNote());
        if (request.getReason() == ManualConfirmReason.OTHER && note == null) {
            throw new AppException(ErrorCode.MANUAL_CONFIRM_NOTE_REQUIRED);
        }
        Session session = lookup.activeSessionOfPlacement(placementId);
        PackagePlacement placement = lookup.placement(session, placementId);
        requireNext(session, placement);
        CargoPackage pkg = placement.getCargoPackage();
        DeliveryStop stop = lookup.stopOf(placement);
        if (pkg == null || stop == null) {
            throw new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        // Kiện ghi đã xếp ngay để kho làm tiếp; điều phối viên từ chối thì lần xếp này bị gỡ
        placementConfirmationRepository.save(PlacementConfirmation.builder()
                .placement(placement)
                .confirmedBy(session.worker())
                .confirmedAt(now)
                .build());
        if (pkg.getStatus() == PackageStatus.PENDING || pkg.getStatus() == PackageStatus.PLANNED) {
            pkg.setStatus(PackageStatus.LOADED);
            cargoPackageRepository.save(pkg);
        }
        ManualUnloadConfirmation confirmation = manualConfirmationRepository.save(ManualUnloadConfirmation.builder()
                .trip(session.trip())
                .deliveryStop(stop)
                .cargoPackage(pkg)
                .placement(placement)
                .reason(request.getReason())
                .note(note)
                .status(ManualConfirmStatus.PENDING)
                .step(ManualConfirmStep.LOADING)
                .requestedBy(session.worker())
                .requestedAt(now)
                .build());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("placementId", placementId);
        values.put("tripId", session.trip().getId());
        values.put("step", ManualConfirmStep.LOADING.name());
        values.put("reason", request.getReason().name());
        lookup.audit(session.trip(), session.worker(), "MANUAL_CONFIRM_REQUESTED", "ManualUnloadConfirmation",
                confirmation.getId(), values);
        log.info("Worker {} requested manual loading confirmation for placement {}", session.worker().getId(), placementId);
        return DriverDeliveryServiceImpl.toResponse(confirmation);
    }

    @Override
    @Transactional
    public DamageResponse reportDamaged(Long placementId, ReportDamageRequest request) {
        Session session = lookup.activeSessionOfPlacement(placementId);
        PackagePlacement placement = lookup.placement(session, placementId);
        requireNext(session, placement);
        CargoPackage pkg = placement.getCargoPackage();
        if (pkg == null) {
            throw new AppException(ErrorCode.PLACEMENT_NOT_FOUND);
        }
        int resting = PlacementSupport.restingCount(placement, session.placements());
        DamageOutcome outcome = resting == 0 ? DamageOutcome.LEFT_OUT : DamageOutcome.REPLANNED;
        LoadingDamage damage = loadingDamageRepository.save(LoadingDamage.builder()
                .trip(session.trip())
                .placement(placement)
                .cargoPackage(pkg)
                .outcome(outcome)
                .restingCount(resting)
                .note(request != null ? WarehouseSessionLookup.trimmed(request.getNote()) : null)
                .reportedBy(session.worker())
                .reportedAt(LocalDateTime.now())
                .build());
        PlacementStepResponse next = null;
        if (outcome == DamageOutcome.LEFT_OUT) {
            loadingReplanService.leaveOut(session.trip(), pkg, PackageFlag.DAMAGED);
            next = session.placements().stream()
                    .filter(entry -> entry.getStepSequence() > placement.getStepSequence())
                    .filter(entry -> !lookup.handled(entry))
                    .findFirst()
                    .map(entry -> PlacementStepResponse.builder()
                            .placementId(entry.getId())
                            .packageId(entry.getCargoPackage() != null ? entry.getCargoPackage().getId() : null)
                            .sequence(entry.getStepSequence())
                            .status(PlacementStepResponse.STATUS_PENDING)
                            .build())
                    .orElse(null);
        } else {
            loadingReplanService.returnToPlanning(session.trip(), session.execution(), pkg, PackageFlag.DAMAGED, session.worker());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", pkg.getId());
        values.put("placementId", placementId);
        values.put("tripId", session.trip().getId());
        values.put("outcome", outcome.name());
        values.put("restingCount", resting);
        values.put("driverId", session.trip().getDriver() != null ? session.trip().getDriver().getId() : null);
        lookup.audit(session.trip(), session.worker(), "LOADING_DAMAGE_REPORTED", "LoadingDamage", damage.getId(), values);
        log.info("Worker {} reported placement {} damaged: {}", session.worker().getId(), placementId, outcome);
        return DamageResponse.builder()
                .placementId(placementId)
                .packageId(pkg.getId())
                .outcome(outcome.name())
                .restingCount(resting)
                .tripStatus(session.trip().getStatus() != null ? session.trip().getStatus().name() : null)
                .nextPlacement(next)
                .build();
    }

    @Override
    @Transactional
    public SealResponse recordSeal(Long tripId, RecordSealRequest request) {
        String seal = request != null ? WarehouseSessionLookup.trimmed(request.getSealNumber()) : null;
        if (seal == null || seal.length() > MAX_SEAL_LENGTH) {
            throw new AppException(ErrorCode.SEAL_INVALID);
        }
        Session session;
        try {
            session = lookup.session(tripId);
        } catch (AppException error) {
            if (error.getErrorCode() == ErrorCode.TRIP_NOT_FOUND) {
                throw error;
            }
            throw new AppException(ErrorCode.SEAL_NOT_ALLOWED);
        }
        LoadingExecution execution = session.execution();
        if (execution.getStatus() != LoadingExecutionStatus.COMPLETED) {
            throw new AppException(ErrorCode.SEAL_NOT_ALLOWED);
        }
        String before = execution.getSealNumber();
        execution.setSealNumber(seal);
        execution.setSealedAt(LocalDateTime.now());
        execution.setSealedBy(session.worker());
        loadingExecutionRepository.save(execution);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tripId", tripId);
        values.put("sealNumber", seal);
        if (before != null) {
            values.put("previousSealNumber", before);
        }
        lookup.audit(session.trip(), session.worker(), "LOADING_SEAL_RECORDED", "LoadingExecution", execution.getId(), values);
        return SealResponse.builder()
                .tripId(tripId)
                .executionId(execution.getId())
                .sealNumber(seal)
                .sealedAt(execution.getSealedAt())
                .build();
    }

    /** Chỉ vị trí kế tiếp: chưa xử lý, mọi vị trí trước đã xử lý, và (phiên có soạn hàng) đã soạn đủ. */
    private void requireNext(Session session, PackagePlacement placement) {
        if (placementConfirmationRepository.existsByPlacementId(placement.getId())) {
            throw new AppException(ErrorCode.PLACEMENT_ALREADY_CONFIRMED);
        }
        if (deviationRepository.existsByPlacementId(placement.getId()) || loadingDamageRepository.existsByPlacementId(placement.getId())) {
            throw new AppException(ErrorCode.DEVIATION_ALREADY_RECORDED);
        }
        lookup.requireStagingComplete(session);
        lookup.requireInSequence(session, placement);
    }
}
