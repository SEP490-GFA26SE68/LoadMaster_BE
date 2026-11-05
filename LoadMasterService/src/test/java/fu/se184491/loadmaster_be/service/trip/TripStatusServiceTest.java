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

    private Trip stubTrip(TripStatus status) {
        Trip trip = Trip.builder().id(10L).status(status).build();
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        return trip;
    }
}
