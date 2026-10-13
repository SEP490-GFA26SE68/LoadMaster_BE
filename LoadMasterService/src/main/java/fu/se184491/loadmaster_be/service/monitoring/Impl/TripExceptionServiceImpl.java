package fu.se184491.loadmaster_be.service.monitoring.Impl;

import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.monitoring.ReportExceptionRequest;
import fu.se184491.loadmaster_be.dto.request.monitoring.ResolveExceptionRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.monitoring.TripException;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.monitoring.TripExceptionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.monitoring.MonitoringTime;
import fu.se184491.loadmaster_be.service.monitoring.TripExceptionService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TripExceptionServiceImpl implements TripExceptionService {

    private final TripExceptionRepository tripExceptionRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final UserRepository userRepository;
    private final TripLookupHelper tripLookupHelper;

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
