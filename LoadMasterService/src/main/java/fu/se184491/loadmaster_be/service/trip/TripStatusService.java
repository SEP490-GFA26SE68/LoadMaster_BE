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
 * IN_TRANSIT → DELIVERED   mọi điểm giao COMPLETED
 * mọi trạng thái trừ DELIVERED, CANCELLED → CANCELLED
 * </pre>
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

    /**
     * @param userId người làm, ghi vào nhật ký; {@code null} khi là việc của hệ thống.
     */
    @Transactional
    public Trip transitionTo(Long tripId, TripStatus newStatus, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
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
