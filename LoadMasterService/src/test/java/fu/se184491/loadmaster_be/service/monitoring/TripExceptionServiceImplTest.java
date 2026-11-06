package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.constant.monitoring.TripExceptionStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.monitoring.RenegotiateDeadlineRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.TripExceptionResponse;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
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
import fu.se184491.loadmaster_be.service.monitoring.Impl.TripExceptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripExceptionServiceImplTest {

    @Mock private TripExceptionRepository tripExceptionRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private UserRepository userRepository;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private DeliveryRequirementRepository deliveryRequirementRepository;
    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks
    private TripExceptionServiceImpl service;

    private Trip trip;
    private TripException exception;
    private DeliveryRequirement requirement;
    private LocalDateTime oldDeadline;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        trip = Trip.builder().id(10L).tripCode("TRIP-10").company(company).status(TripStatus.IN_TRANSIT).build();
        exception = TripException.builder().id(5L).trip(trip).status(TripExceptionStatus.ESCALATED)
                .reportedAt(LocalDateTime.now().minusHours(1)).build();
        oldDeadline = LocalDateTime.now().plusHours(1).withNano(0);
        requirement = DeliveryRequirement.builder().id(30L).company(company).trip(trip).deadline(oldDeadline).build();
        when(tripLookupHelper.requireTrip("10", 1L)).thenReturn(trip);
        when(tripExceptionRepository.findByIdAndTripId(5L, 10L)).thenReturn(Optional.of(exception));
        lenient().when(deliveryRequirementRepository.findByIdAndCompanyId(30L, 1L)).thenReturn(Optional.of(requirement));
    }

    private RenegotiateDeadlineRequest request(LocalDateTime deadline, String note) {
        return RenegotiateDeadlineRequest.builder().requirementId(30L).newDeadline(deadline).contactNote(note).build();
    }

    @Test
    void renegotiatingUpdatesRequirementAndOpenStopsAndLogs() {
        LocalDateTime newDeadline = LocalDateTime.now().plusHours(4).withNano(0);
        DeliveryStop open = DeliveryStop.builder().id(40L).status(DeliveryStopStatus.PENDING)
                .plannedArrival(oldDeadline).build();
        DeliveryStop done = DeliveryStop.builder().id(41L).status(DeliveryStopStatus.COMPLETED)
                .plannedArrival(oldDeadline).build();
        when(deliveryStopRepository.findByTripIdAndDeliveryRequirementId(10L, 30L)).thenReturn(List.of(open, done));

        TripExceptionResponse response = service.renegotiateDeadline(
                "10", "EXC-5", 1L, request(newDeadline, "Đã gọi khách, đồng ý nhận chiều nay"), null);

        assertEquals("ESCALATED", response.getStatus());
        assertEquals(newDeadline, requirement.getDeadline());
        assertEquals(newDeadline, open.getPlannedArrival());
        assertEquals(oldDeadline, done.getPlannedArrival());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("DEADLINE_RENEGOTIATED", audit.getValue().getActionType());
        assertEquals(1L, audit.getValue().getCompanyId());
        assertEquals(10L, audit.getValue().getNewValues().get("tripId"));
        assertEquals(30L, audit.getValue().getNewValues().get("requirementId"));
        assertEquals(oldDeadline.toString(), audit.getValue().getNewValues().get("oldDeadline"));
        assertEquals(newDeadline.toString(), audit.getValue().getNewValues().get("newDeadline"));
    }

    @Test
    void openExceptionCannotBeRenegotiated() {
        exception.setStatus(TripExceptionStatus.OPEN);

        AppException error = assertThrows(AppException.class, () -> service.renegotiateDeadline(
                "10", "5", 1L, request(LocalDateTime.now().plusHours(4), "Đã gọi khách"), null));

        assertEquals(ErrorCode.EXCEPTION_STATUS_INVALID, error.getErrorCode());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void contactNoteIsRequired() {
        AppException error = assertThrows(AppException.class, () -> service.renegotiateDeadline(
                "10", "5", 1L, request(LocalDateTime.now().plusHours(4), "   "), null));

        assertEquals(ErrorCode.CONTACT_NOTE_REQUIRED, error.getErrorCode());
        assertEquals(oldDeadline, requirement.getDeadline());
    }

    @Test
    void pastDeadlineIsRejected() {
        AppException error = assertThrows(AppException.class, () -> service.renegotiateDeadline(
                "10", "5", 1L, request(LocalDateTime.now().minusMinutes(5), "Đã gọi khách"), null));

        assertEquals(ErrorCode.REQUIREMENT_DEADLINE_PAST, error.getErrorCode());
    }

    @Test
    void requirementOfAnotherTripIsRejected() {
        requirement.setTrip(Trip.builder().id(99L).build());

        AppException error = assertThrows(AppException.class, () -> service.renegotiateDeadline(
                "10", "5", 1L, request(LocalDateTime.now().plusHours(4), "Đã gọi khách"), null));

        assertEquals(ErrorCode.DELIVERY_REQUIREMENT_NOT_IN_TRIP, error.getErrorCode());
        assertEquals(oldDeadline, requirement.getDeadline());
    }
}
