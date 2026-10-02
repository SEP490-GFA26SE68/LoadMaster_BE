package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.InvalidTripStatusTransitionException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TripStatusService {

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final DeliveryStopRepository deliveryStopRepository;

    @Transactional
    public Trip transitionTo(Long tripId, TripStatus newStatus, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        TripStatus currentStatus = trip.getStatus();
        boolean validTransition = currentStatus == TripStatus.DRAFT
                && newStatus == TripStatus.PLANNED
                && trip.getRoutePlan() != null
                && !trip.getRoutePlan().isEmpty();
        if (currentStatus == TripStatus.PLANNED && newStatus == TripStatus.LOADING) {
            validTransition = loadingExecutionRepository.findByLoadPlanJobTripId(tripId).isPresent();
        }
        if (currentStatus == TripStatus.LOADING && newStatus == TripStatus.IN_TRANSIT) {
            validTransition = loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(
                    tripId, LoadingExecutionStatus.COMPLETED);
        }
        if (currentStatus == TripStatus.IN_TRANSIT && newStatus == TripStatus.DELIVERED) {
            validTransition = deliveryStopRepository.countByTripId(tripId) > 0
                    && deliveryStopRepository
                    .findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(
                            tripId, DeliveryStopStatus.COMPLETED)
                    .isEmpty();
        }
        if (newStatus == TripStatus.CANCELLED && currentStatus != TripStatus.CANCELLED) {
            validTransition = true;
        }
        if (!validTransition) {
            throw new InvalidTripStatusTransitionException(currentStatus, newStatus);
        }
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        trip.setStatus(newStatus);
        Trip savedTrip = tripRepository.save(trip);
        auditLogRepository.save(AuditLog.builder()
                .actionType("TRIP_STATUS_TRANSITION")
                .entityName("Trip")
                .entityId(tripId.toString())
                .user(actor)
                .oldValues(Map.of("status", currentStatus.name()))
                .newValues(Map.of("status", newStatus.name()))
                .createdAt(LocalDateTime.now())
                .build());
        return savedTrip;
    }
}
