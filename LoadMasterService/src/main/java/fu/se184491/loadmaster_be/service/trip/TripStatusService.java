package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.InvalidTripStatusTransitionException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Nơi duy nhất đổi {@link Trip#getStatus()}.
 *
 * <pre>
 * DRAFT      → PLANNED     đã tối ưu tuyến (route_plan có dữ liệu)
 * PLANNED    → DRAFT       điểm giao / kho xuất phát / giờ xuất phát đổi: tuyến cũ không còn đúng
 * PLANNED    → LOADING     kho đã mở phiên xếp (loading execution IN_PROGRESS)
 * LOADING    → IN_TRANSIT  kho đã xếp xong (loading execution COMPLETED), tài xế xuất phát
 * LOADING    → PLANNED     phiên xếp dừng (ABORTED) vì kiện bị bỏ khỏi chuyến: thiếu lúc soạn, hoặc hỏng mà có kiện tựa lên;
 *                          không còn phiên IN_PROGRESS / COMPLETED, chuyến chờ tối ưu lại
 * IN_TRANSIT → DELIVERED   mọi điểm giao COMPLETED
 * mọi trạng thái trừ DELIVERED, CANCELLED → CANCELLED
 * </pre>
 *
 * Mọi lần đổi trạng thái khoá dòng chuyến (SELECT … FOR UPDATE), nên huỷ, xuất phát, hoàn tất điểm, bắt đầu xếp,
 * duyệt… trên cùng một chuyến chạy lần lượt. Lệnh nào kiểm trạng thái trước khi đổi thì gọi
 * {@link #lockForTransition} ngay đầu transaction.
 */
@Service
@RequiredArgsConstructor
public class TripStatusService {

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final RoutePlanRepository routePlanRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Khoá dòng chuyến tới hết transaction rồi đọc lại từ DB: lệnh khác có thể vừa đổi chuyến trong lúc chờ khoá.
     * Gọi ở đầu lệnh, trước khi sửa gì trên chuyến (đọc lại bỏ thay đổi chưa ghi của chuyến).
     *
     * @param readBefore bản ghi của chuyến lệnh đã đọc trước khi có khoá (điểm giao, phiên xếp…), đọc lại cùng lúc
     */
    @Transactional
    public Trip lockForTransition(Long tripId, Object... readBefore) {
        Trip trip = lockedTrip(tripId);
        if (entityManager != null) {
            entityManager.refresh(trip);
            for (Object entity : readBefore) {
                if (entity != null) {
                    entityManager.refresh(entity);
                }
            }
        }
        return trip;
    }

    /**
     * @param userId người làm, ghi vào nhật ký; {@code null} khi là việc của hệ thống.
     */
    @Transactional
    public Trip transitionTo(Long tripId, TripStatus newStatus, Long userId) {
        Trip trip = lockedTrip(tripId);
        TripStatus currentStatus = trip.getStatus();
        if (!isValidTransition(trip, currentStatus, newStatus)) {
            throw new InvalidTripStatusTransitionException(currentStatus, newStatus);
        }
        User actor = userId == null
                ? null
                : userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        trip.setStatus(newStatus);
        Trip savedTrip = tripRepository.save(trip);
        Map<String, Object> newValues = new java.util.HashMap<>();
        newValues.put("status", newStatus.name());
        // Tài xế lúc chuyển: chuông của tài xế đọc đúng người của chuyến khi đó
        if (trip.getDriver() != null) {
            newValues.put("driverId", trip.getDriver().getId());
        }
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .actionType("TRIP_STATUS_TRANSITION")
                .entityName("Trip")
                .entityId(tripId.toString())
                .user(actor)
                .oldValues(Map.of("status", currentStatus.name()))
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());
        return savedTrip;
    }

    /**
     * Điểm giao hoặc điểm xuất phát của chuyến vừa đổi: tuyến đã tối ưu không còn đúng.
     * Bỏ tuyến (JSON trên chuyến, kế hoạch tuyến đang dùng, giờ đến dự kiến của điểm) và,
     * nếu chuyến đang Đã lập kế hoạch, đưa về Nháp. Chuyến Nháp chỉ bỏ tuyến.
     */
    @Transactional
    public void invalidateRoute(Trip trip, Long userId) {
        trip.setRoutePlan(null);
        routePlanRepository.deactivateAllByTripId(trip.getId());
        for (DeliveryStop stop : deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId())) {
            stop.setPlannedArrival(null);
        }
        if (trip.getStatus() == TripStatus.PLANNED) {
            transitionTo(trip.getId(), TripStatus.DRAFT, userId);
        } else {
            tripRepository.save(trip);
        }
    }

    private Trip lockedTrip(Long tripId) {
        return tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
    }

    private boolean isValidTransition(Trip trip, TripStatus currentStatus, TripStatus newStatus) {
        Long tripId = trip.getId();
        if (currentStatus == TripStatus.DRAFT && newStatus == TripStatus.PLANNED) {
            return trip.getRoutePlan() != null && !trip.getRoutePlan().isEmpty();
        }
        if (currentStatus == TripStatus.PLANNED && newStatus == TripStatus.DRAFT) {
            return true;
        }
        if (currentStatus == TripStatus.PLANNED && newStatus == TripStatus.LOADING) {
            return loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(
                    tripId, LoadingExecutionStatus.IN_PROGRESS);
        }
        if (currentStatus == TripStatus.LOADING && newStatus == TripStatus.IN_TRANSIT) {
            return loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(
                    tripId, LoadingExecutionStatus.COMPLETED);
        }
        if (currentStatus == TripStatus.LOADING && newStatus == TripStatus.PLANNED) {
            return !loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(tripId, LoadingExecutionStatus.IN_PROGRESS)
                    && !loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(tripId, LoadingExecutionStatus.COMPLETED);
        }
        if (currentStatus == TripStatus.IN_TRANSIT && newStatus == TripStatus.DELIVERED) {
            return deliveryStopRepository.countByTripId(tripId) > 0
                    && deliveryStopRepository
                    .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(
                            tripId, DeliveryStopStatus.COMPLETED)
                    .isEmpty();
        }
        if (newStatus == TripStatus.CANCELLED) {
            return currentStatus != TripStatus.CANCELLED && currentStatus != TripStatus.DELIVERED;
        }
        return false;
    }
}
