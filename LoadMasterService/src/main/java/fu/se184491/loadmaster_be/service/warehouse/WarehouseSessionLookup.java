package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tìm phiên xếp đang mở của kho cho các việc ngoài xác nhận vị trí (soạn hàng, báo thiếu, xác nhận tay, kiện hỏng, seal): chuyến của công
 * ty người đăng nhập, phương án đã duyệt mới nhất và phiên xếp của nó.
 */
@Component
@RequiredArgsConstructor
public class WarehouseSessionLookup {

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final TripPackageResolver tripPackageResolver;
    private final WarehouseProgress warehouseProgress;
    private final AuditLogRepository auditLogRepository;

    /** Phiên của một chuyến: người làm, chuyến, phương án đang xếp, phiên xếp và các vị trí theo thứ tự xếp. */
    public record Session(User worker, Trip trip, LoadPlan plan, LoadingExecution execution, List<PackagePlacement> placements) {
    }

    /** Phiên của chuyến (Đang xếp hàng) thuộc công ty người đăng nhập, mọi trạng thái phiên. */
    public Session session(Long tripId) {
        User worker = currentUserService.getCurrentUser();
        if (worker.getCompany() == null) {
            throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
        }
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, worker.getCompany().getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        if (trip.getStatus() != TripStatus.LOADING) {
            throw new AppException(ErrorCode.TRIP_NOT_LOADING);
        }
        LoadPlan plan = loadPlanRepository.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));
        LoadingExecution execution = loadingExecutionRepository.findByLoadPlanId(plan.getId())
                .orElseThrow(() -> new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND));
        return new Session(worker, trip, plan, execution,
                packagePlacementRepository.findByLoadPlanIdOrderByStepSequenceAsc(plan.getId()));
    }

    /** Phiên đang xếp ({@code IN_PROGRESS}) của chuyến. */
    public Session activeSession(Long tripId) {
        Session session = session(tripId);
        if (session.execution().getStatus() != LoadingExecutionStatus.IN_PROGRESS) {
            throw new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND);
        }
        return session;
    }

    /** Phiên đang xếp chứa vị trí này. */
    public Session activeSessionOfPlacement(Long placementId) {
        PackagePlacement placement = packagePlacementRepository.findById(placementId)
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));
        Trip trip = placement.getLoadPlan() != null && placement.getLoadPlan().getJob() != null
                ? placement.getLoadPlan().getJob().getTrip()
                : null;
        if (trip == null) {
            throw new AppException(ErrorCode.PLACEMENT_NOT_FOUND);
        }
        Session session;
        try {
            session = activeSession(trip.getId());
        } catch (AppException error) {
            if (error.getErrorCode() == ErrorCode.TRIP_NOT_FOUND) {
                throw new AppException(ErrorCode.PLACEMENT_NOT_FOUND);
            }
            throw new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND);
        }
        if (!session.plan().getId().equals(placement.getLoadPlan().getId())) {
            throw new AppException(ErrorCode.LOADING_EXECUTION_NOT_FOUND);
        }
        return session;
    }

    public PackagePlacement placement(Session session, Long placementId) {
        return session.placements().stream()
                .filter(entry -> placementId.equals(entry.getId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));
    }

    /** Vị trí của kiện trong phương án đang xếp. */
    public PackagePlacement placementOfPackage(Session session, Long packageId) {
        return session.placements().stream()
                .filter(entry -> entry.getCargoPackage() != null && packageId.equals(entry.getCargoPackage().getId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_IN_LOADING_PLAN));
    }

    /** Vị trí đã xử lý: đã xác nhận, đã ghi sai lệch, hoặc là kiện hỏng bị bỏ lại kho. */
    public boolean handled(PackagePlacement placement) {
        return placementConfirmationRepository.existsByPlacementId(placement.getId())
                || deviationRepository.existsByPlacementId(placement.getId())
                || warehouseProgress.isLeftOut(placement.getId());
    }

    /** Mọi vị trí đứng trước vị trí này theo thứ tự xếp phải đã xử lý. */
    public void requireInSequence(Session session, PackagePlacement placement) {
        for (PackagePlacement entry : session.placements()) {
            if (entry.getStepSequence() < placement.getStepSequence() && !handled(entry)) {
                throw new AppException(ErrorCode.INVALID_SEQUENCE_ORDER);
            }
        }
    }

    public void requireStagingComplete(Session session) {
        warehouseProgress.requireStagingComplete(session.execution(), session.trip().getId(), session.placements());
    }

    /** Điểm giao thật của kiện (qua yêu cầu giao), không có thì vùng điểm giao của vị trí. */
    public DeliveryStop stopOf(PackagePlacement placement) {
        CargoPackage pkg = placement.getCargoPackage();
        if (pkg != null) {
            DeliveryStop stop = tripPackageResolver.stopOfPackage(pkg).orElse(null);
            if (stop != null) {
                return stop;
            }
        }
        return placement.getStopZone();
    }

    public void audit(Trip trip, User user, String action, String entity, Long entityId, Map<String, Object> values) {
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType(action)
                .entityName(entity)
                .entityId(String.valueOf(entityId))
                .user(user)
                .newValues(values)
                .createdAt(Instant.now())
                .build());
    }

    public static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
