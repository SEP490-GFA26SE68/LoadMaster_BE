package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.constant.monitoring.ExceptionEscalationReason;
import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.monitoring.RenegotiateDeadlineRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ReportExceptionRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ResolveExceptionRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TripExceptionServiceImpl implements TripExceptionService {

    private final TripExceptionRepository tripExceptionRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final UserRepository userRepository;
    private final TripLookupHelper tripLookupHelper;
    private final DeliveryRequirementRepository deliveryRequirementRepository;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public TripExceptionResponse renegotiateDeadline(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            RenegotiateDeadlineRequest request,
            Long managerUserId
    ) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        TripException exception = tripExceptionRepository.findByIdAndTripId(parseExceptionId(exceptionIdOrCode), trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND));
        if (exception.getStatus() != TripExceptionStatus.ESCALATED) {
            throw new AppException(ErrorCode.EXCEPTION_STATUS_INVALID);
        }
        if (request == null || request.getRequirementId() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        String contactNote = request.getContactNote() == null ? "" : request.getContactNote().trim();
        if (contactNote.isEmpty()) {
            throw new AppException(ErrorCode.CONTACT_NOTE_REQUIRED);
        }
        LocalDateTime newDeadline = request.getNewDeadline();
        if (newDeadline == null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        if (!newDeadline.isAfter(MonitoringTime.now())) {
            throw new AppException(ErrorCode.REQUIREMENT_DEADLINE_PAST);
        }

        DeliveryRequirement requirement = deliveryRequirementRepository
                .findByIdAndCompanyId(request.getRequirementId(), companyId)
                .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_FOUND));
        if (requirement.getTrip() == null || !Objects.equals(requirement.getTrip().getId(), trip.getId())) {
            throw new AppException(ErrorCode.DELIVERY_REQUIREMENT_NOT_IN_TRIP);
        }

        LocalDateTime oldDeadline = requirement.getDeadline();
        requirement.setDeadline(newDeadline);
        deliveryRequirementRepository.save(requirement);

        // Giám sát đọc hạn của điểm giao ở giờ đến kế hoạch: điểm chưa giao của yêu cầu này nhận hạn mới.
        for (DeliveryStop stop : deliveryStopRepository.findByTripIdAndDeliveryRequirementId(trip.getId(), requirement.getId())) {
            if (stop.getStatus() != DeliveryStopStatus.COMPLETED) {
                stop.setPlannedArrival(newDeadline);
                deliveryStopRepository.save(stop);
            }
        }

        User manager = managerUserId != null ? userRepository.findById(managerUserId).orElse(null) : null;
        Map<String, Object> oldValues = new LinkedHashMap<>();
        oldValues.put("deadline", oldDeadline != null ? oldDeadline.toString() : null);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("tripId", trip.getId());
        newValues.put("exceptionId", exception.getId());
        newValues.put("requirementId", requirement.getId());
        newValues.put("oldDeadline", oldDeadline != null ? oldDeadline.toString() : null);
        newValues.put("newDeadline", newDeadline.toString());
        newValues.put("contactNote", contactNote);
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofTrip(trip))
                .user(manager)
                .actionType("DEADLINE_RENEGOTIATED")
                .entityName("TripException")
                .entityId(String.valueOf(exception.getId()))
                .oldValues(oldValues)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());

        return toResponse(exception);
    }

    @Override
    @Transactional
    public TripExceptionResponse report(
            String tripIdOrCode,
            Long companyId,
            ReportExceptionRequest request,
            Long reporterUserId
    ) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        if (trip.getStatus() != TripStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT);
        }

        Integer stopNumber = deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId()).stream()
                .filter(stop -> EnumSet.of(DeliveryStopStatus.PENDING, DeliveryStopStatus.ARRIVED).contains(stop.getStatus()))
                .map(DeliveryStop::getStopSequence)
                .findFirst()
                .orElse(null);

        User reporter = reporterUserId != null
                ? userRepository.findById(reporterUserId).orElse(null)
                : null;

        TripException exception = TripException.builder()
                .trip(trip)
                .type(request.getType())
                .description(request.getDescription())
                .delayMinutes(request.getDelayMinutes())
                .status(TripExceptionStatus.OPEN)
                .stopNumber(stopNumber)
                .reportedAt(MonitoringTime.now())
                .reportedBy(reporter)
                .build();

        return toResponse(tripExceptionRepository.save(exception));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripExceptionResponse> list(String tripIdOrCode, Long companyId) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        return tripExceptionRepository.findByTripIdOrderByReportedAtAsc(trip.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public TripExceptionResponse resolve(
            String tripIdOrCode,
            Long exceptionId,
            Long companyId,
            ResolveExceptionRequest request,
            Long resolverUserId
    ) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        TripException exception = tripExceptionRepository.findByIdAndTripId(exceptionId, trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND));

        return resolveEntity(exception, request, resolverUserId);
    }

    @Override
    @Transactional
    public TripExceptionResponse resolveByPathId(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            ResolveExceptionRequest request,
            Long resolverUserId
    ) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        Long exceptionId = parseExceptionId(exceptionIdOrCode);
        TripException exception = tripExceptionRepository.findByIdAndTripId(exceptionId, trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND));
        return resolveEntity(exception, request, resolverUserId);
    }

    @Override
    @Transactional
    public TripExceptionResponse escalate(
            String tripIdOrCode,
            String exceptionIdOrCode,
            Long companyId,
            Long escalatedByUserId,
            String reason
    ) {
        Trip trip = tripLookupHelper.requireTrip(tripIdOrCode, companyId);
        TripException exception = tripExceptionRepository.findByIdAndTripId(parseExceptionId(exceptionIdOrCode), trip.getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND));
        String resolvedReason = reason == null || reason.isBlank()
                ? ExceptionEscalationReason.NO_ROUTE.name()
                : reason.trim();
        return escalateEntity(exception, resolvedReason, escalatedByUserId);
    }

    @Override
    @Transactional
    public int escalateOverdue() {
        var deadline = MonitoringTime.now().minusMinutes(ExceptionEscalationReason.AFTER_MINUTES);
        List<TripException> overdue = tripExceptionRepository.findByStatusAndReportedAtBefore(
                TripExceptionStatus.OPEN, deadline);
        int count = 0;
        for (TripException exception : overdue) {
            escalateEntity(exception, ExceptionEscalationReason.TIMEOUT.name(), null);
            count++;
        }
        return count;
    }

    private TripExceptionResponse escalateEntity(TripException exception, String reason, Long userId) {
        if (exception.getStatus() != TripExceptionStatus.OPEN) {
            throw new AppException(ErrorCode.EXCEPTION_STATUS_INVALID);
        }
        User escalatedBy = userId != null
                ? userRepository.findById(userId).orElse(null)
                : null;
        exception.setStatus(TripExceptionStatus.ESCALATED);
        exception.setEscalationReason(reason);
        exception.setEscalatedAt(MonitoringTime.now());
        exception.setEscalatedBy(escalatedBy);
        return toResponse(tripExceptionRepository.save(exception));
    }

    private TripExceptionResponse resolveEntity(
            TripException exception,
            ResolveExceptionRequest request,
            Long resolverUserId
    ) {

        User resolver = resolverUserId != null
                ? userRepository.findById(resolverUserId).orElse(null)
                : null;

        exception.setStatus(TripExceptionStatus.RESOLVED);
        exception.setResolvedAt(MonitoringTime.now());
        exception.setResolvedBy(resolver);
        if (request != null) {
            exception.setResolutionNote(request.getNote());
        }

        return toResponse(tripExceptionRepository.save(exception));
    }

    private Long parseExceptionId(String exceptionIdOrCode) {
        if (exceptionIdOrCode == null || exceptionIdOrCode.isBlank()) {
            throw new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND);
        }
        String trimmed = exceptionIdOrCode.trim();
        if (trimmed.regionMatches(true, 0, "EXC-", 0, 4)) {
            trimmed = trimmed.substring(4);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new AppException(ErrorCode.TRIP_EXCEPTION_NOT_FOUND);
        }
    }

    TripExceptionResponse toResponse(TripException exception) {
        String tripCode = exception.getTrip() != null ? exception.getTrip().getTripCode() : null;

        TripExceptionResponse.TripExceptionResponseBuilder builder = TripExceptionResponse.builder()
                .id("EXC-" + exception.getId())
                .tripId(tripCode)
                .type(exception.getType() != null ? exception.getType().name() : null)
                .description(exception.getDescription())
                .delayMinutes(exception.getDelayMinutes())
                .status(exception.getStatus() != null ? exception.getStatus().name() : null)
                .stopNumber(exception.getStopNumber())
                .reportedAt(MonitoringTime.toIso(exception.getReportedAt()))
                .reportedBy(userId(exception.getReportedBy()));

        if (exception.getEscalationReason() != null) {
            builder.escalation(TripExceptionResponse.EscalationInfo.builder()
                    .reason(exception.getEscalationReason())
                    .at(MonitoringTime.toIso(exception.getEscalatedAt()))
                    .by(userId(exception.getEscalatedBy()))
                    .build());
        }

        if (exception.getStatus() == TripExceptionStatus.RESOLVED && exception.getResolvedAt() != null) {
            builder.resolution(TripExceptionResponse.ResolutionInfo.builder()
                    .at(MonitoringTime.toIso(exception.getResolvedAt()))
                    .by(userId(exception.getResolvedBy()))
                    .note(exception.getResolutionNote())
                    .build());
        }

        return builder.build();
    }

    private String userId(User user) {
        return Optional.ofNullable(user).map(u -> String.valueOf(u.getId())).orElse(null);
    }
}
