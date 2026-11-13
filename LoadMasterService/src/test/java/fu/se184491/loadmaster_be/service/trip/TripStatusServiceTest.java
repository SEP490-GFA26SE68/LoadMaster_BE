package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.InvalidTripStatusTransitionException;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripStatusServiceTest {

    @Mock private TripRepository tripRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private RoutePlanRepository routePlanRepository;

    @InjectMocks
    private TripStatusService service;

    @Test
    void deliveredTripCannotBeCancelled() {
        stubTrip(TripStatus.DELIVERED);

        InvalidTripStatusTransitionException error = assertThrows(
                InvalidTripStatusTransitionException.class,
                () -> service.transitionTo(10L, TripStatus.CANCELLED, null));

        assertInstanceOf(AppException.class, error);
        assertEquals(ErrorCode.INVALID_TRIP_STATUS_TRANSITION, error.getErrorCode());
    }

    @Test
    void systemTransitionNeedsNoActor() {
        Trip trip = stubTrip(TripStatus.LOADING);
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.transitionTo(10L, TripStatus.CANCELLED, null);

        assertEquals(TripStatus.CANCELLED, trip.getStatus());
    }

    @Test
    void transitionLogBelongsToCompanyOfTheTrip() {
        Trip trip = stubTrip(TripStatus.LOADING);
        trip.setCompany(fu.se184491.loadmaster_be.entity.company.Company.builder().id(4L).build());
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.transitionTo(10L, TripStatus.CANCELLED, null);

        org.mockito.ArgumentCaptor<fu.se184491.loadmaster_be.entity.common.AuditLog> log =
                org.mockito.ArgumentCaptor.forClass(fu.se184491.loadmaster_be.entity.common.AuditLog.class);
        verify(auditLogRepository).save(log.capture());
        assertEquals(4L, log.getValue().getCompanyId());
    }

    @Test
    void invalidatingRouteSendsPlannedTripBackToDraft() {
        Trip trip = stubTrip(TripStatus.PLANNED);
        Map<String, Object> route = new HashMap<>();
        route.put("orderedStops", List.of(1L));
        trip.setRoutePlan(route);
        DeliveryStop stop = DeliveryStop.builder().id(30L).plannedArrival(LocalDateTime.now()).build();
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(stop));
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.invalidateRoute(trip, null);

        assertNull(trip.getRoutePlan());
        assertNull(stop.getPlannedArrival());
        assertEquals(TripStatus.DRAFT, trip.getStatus());
        verify(routePlanRepository).deactivateAllByTripId(10L);
    }

    @Test
    void abortedLoadingSendsTheTripBackToPlanned() {
        Trip trip = stubTrip(TripStatus.LOADING);
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.transitionTo(10L, TripStatus.PLANNED, null);

        assertEquals(TripStatus.PLANNED, trip.getStatus());
    }

    @Test
    void openLoadingSessionKeepsTheTripLoading() {
        stubTrip(TripStatus.LOADING);
        when(loadingExecutionRepository.existsByLoadPlanJobTripIdAndStatus(10L,
                fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus.IN_PROGRESS)).thenReturn(true);

        assertThrows(InvalidTripStatusTransitionException.class,
                () -> service.transitionTo(10L, TripStatus.PLANNED, null));
    }

    @Test
    void transitionReadsTheTripThroughTheRowLock() {
        stubTrip(TripStatus.LOADING);
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.transitionTo(10L, TripStatus.CANCELLED, null);

        // Đọc chuyến bằng SELECT … FOR UPDATE: hai lệnh đổi trạng thái cùng chuyến không chạy chồng lên nhau
        verify(tripRepository).findByIdForUpdate(10L);
        verify(tripRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void lockForTransitionLocksTheRow() {
        Trip trip = stubTrip(TripStatus.PLANNED);

        assertEquals(trip, service.lockForTransition(10L));
        verify(tripRepository).findByIdForUpdate(10L);
    }

    @Test
    void missingTripIsNotFoundWhenLocking() {
        when(tripRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        AppException error = assertThrows(AppException.class, () -> service.lockForTransition(99L));
        assertEquals(ErrorCode.TRIP_NOT_FOUND, error.getErrorCode());
    }

    private Trip stubTrip(TripStatus status) {
        Trip trip = Trip.builder().id(10L).status(status).build();
        when(tripRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(trip));
        return trip;
    }
}
